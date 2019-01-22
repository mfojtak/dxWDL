/*

Execute a limitied WDL workflow on the platform. The workflow can
contain declarations, nested if/scatter blocks, and one call
location. A simple example is `wf_scat`.

workflow wf_scat {
    String pattern
    Array[Int] numbers = [1, 3, 7, 15]
    Array[Int] index = range(length(numbers))

    scatter (i in index) {
        call inc {input: i=numbers[i]}
    }

  output {
    Array[Int] inc1_result = inc1.result
  }
}

A nested example:

workflow wf_cond {
   Int x

   if (x > 10) {
      scatter (b in [1, 3, 5, 7])
          call add {input: a=x, b=b}
   }
   output {
      Array[Int]? add_result = add.result
   }
}
*/

package dxWDL.runner

import cats.data.Validated.{Invalid, Valid}
import common.validation.ErrorOr.ErrorOr
import com.dnanexus._
import com.fasterxml.jackson.databind.JsonNode
import scala.collection.JavaConverters._
import spray.json._
import wom.callable.{WorkflowDefinition}
import wom.expression._
import wom.graph._
import wom.graph.GraphNodePort._
import wom.graph.expression._
import wom.values._
import wom.types._

import dxWDL.util._

case class WfFragRunner(wf: WorkflowDefinition,
                        wfSourceCode: String,
                        instanceTypeDB: InstanceTypeDB,
                        execLinkInfo: Map[String, ExecLinkInfo],
                        dxPathConfig : DxPathConfig,
                        dxIoFunctions : DxIoFunctions,
                        runtimeDebugLevel: Int) {
    private val verbose = runtimeDebugLevel >= 1
    //private val maxVerboseLevel = (runtimeDebugLevel == 2)

    var gSeqNum = 0
    private def launchSeqNum() : Int = {
        gSeqNum += 1
        gSeqNum
    }

    private def evaluateWomExpression(expr: WomExpression,
                                      womType: WomType,
                                      env: Map[String, WomValue]) : WomValue = {
        val result: ErrorOr[WomValue] =
            expr.evaluateValue(env, dxIoFunctions)
        val value = result match {
            case Invalid(errors) => throw new Exception(
                s"Failed to evaluate expression ${expr} with ${errors}")
            case Valid(x: WomValue) => x
        }

        // cast the result value to the correct type
        // For example, an expression like:
        //   Float x = "3.2"
        // requires casting from string to float
        womType.coerceRawValue(value).get
    }

    // Figure out what outputs need to be exported.
    private def exportedVarNames() : Set[String] = {
        val dxapp : DXApplet = Utils.dxEnv.getJob().describe().getApplet()
        val desc : DXApplet.Describe = dxapp.describe()
        val outputSpecRaw: List[OutputParameter] = desc.getOutputSpecification().asScala.toList
        val outputNames = outputSpecRaw.map(
            iSpec => iSpec.getName
        )

        // remove auxiliary fields
        outputNames
            .filter( fieldName => !fieldName.endsWith(Utils.FLAT_FILES_SUFFIX))
            .map{ name => Utils.revTransformVarName(name) }
            .toSet
    }

    private def processOutputs(womOutputs: Map[String, WomValue],
                               wvlOutputs: Map[String, WdlVarLinks],
                               exportedVars: Set[String]) : Map[String, JsValue] = {
        Utils.appletLog(verbose, s"""|processOutput
                                     |  exportedVars = ${exportedVars}
                                     |  womOutputs = ${womOutputs.keys}
                                     |  wvlOutputs = ${wvlOutputs.keys}
                                     |""".stripMargin)

        // convert the WOM values to WVLs
        val womWvlOutputs = womOutputs.map{
            case (name, value) =>
                name -> WdlVarLinks.importFromWDL(value.womType, value)
        }.toMap

        // filter anything that should not be exported.
        val exportedWvls = (womWvlOutputs ++ wvlOutputs).filter{
            case (name, wvl) => exportedVars contains name
        }

        // convert from WVL to JSON
        //
        // TODO: check for each variable if it should be output
        exportedWvls.foldLeft(Map.empty[String, JsValue]) {
            case (accu, (varName, wvl)) =>
                val fields = WdlVarLinks.genFields(wvl, varName)
                accu ++ fields.toMap
        }.toMap
    }

 /**
      In the workflow below, we want to correctly pass the [k] value
      to each [inc] Task invocation.
    scatter (k in integers) {
        call inc as inc {input: i=k}
   }
   */
    private def buildAppletInputs(call: CallNode,
                                  linkInfo: ExecLinkInfo,
                                  env : Map[String, WomValue]) : JsValue = {
        Utils.appletLog(verbose, s"""|buildAppletInputs (${call.identifier.localName.value})
                                     |env:
                                     |${env.mkString("\n")}
                                     |""".stripMargin)

        val inputs: Map[String, WomValue] = linkInfo.inputs.flatMap{
            case (varName, wdlType) =>
                env.get(varName) match {
                    case None =>
                        // No binding for this input. It might be optional,
                        // it could have a default value. It could also actually be missing,
                        // which will result in a platform error.
                        None
                    case Some(womValue) =>
                        Some(varName -> womValue)
                }
        }

        val wvlInputs = inputs.map{ case (name, womValue) =>
            val womType = linkInfo.inputs(name)
            name -> WdlVarLinks.importFromWDL(womType, womValue)
        }.toMap

        val m = wvlInputs.foldLeft(Map.empty[String, JsValue]) {
            case (accu, (varName, wvl)) =>
                val fields = WdlVarLinks.genFields(wvl, varName)
                accu ++ fields.toMap
        }
        JsObject(m)
    }

    private def execCall(call: CallNode,
                         env: Map[String, WomValue],
                         linkInfo: ExecLinkInfo,
                         callNameHint: Option[String]) : (Int, DXExecution) = {
        val calleeName = call.callable.name
        val callName = call.identifier.localName.value
        val callInputs:JsValue = buildAppletInputs(call, linkInfo, env)
        Utils.appletLog(verbose, s"""|Call ${callName}
                                     |calleeName= ${calleeName}
                                     |inputs = ${callInputs}""".stripMargin)

        // We may need to run a collect subjob. Add the call
        // name, and the sequence number, to each execution invocation,
        // so the collect subjob will be able to put the
        // results back together.
        val seqNum: Int = launchSeqNum()
        val dbgName = callNameHint match {
            case None => call.identifier.localName.value
            case Some(hint) => s"${callName} ${hint}"
        }
        val dxExecId = linkInfo.dxExec.getId
        val dxExec =
            if (dxExecId.startsWith("app-")) {
                val fields = Map(
                    "name" -> JsString(dbgName),
                    "input" -> callInputs,
                    "properties" -> JsObject("call" ->  JsString(callName),
                                             "seq_number" -> JsString(seqNum.toString))
                )
                val req = JsObject(fields)
                val retval: JsonNode = DXAPI.appRun(dxExecId,
                                                    Utils.jsonNodeOfJsValue(req),
                                                    classOf[JsonNode])
                val info: JsValue =  Utils.jsValueOfJsonNode(retval)
                val id:String = info.asJsObject.fields.get("id") match {
                    case Some(JsString(x)) => x
                    case _ => throw new AppInternalException(
                        s"Bad format returned from jobNew ${info.prettyPrint}")
                }
                DXJob.getInstance(id)
            } else if (dxExecId.startsWith("applet-")) {
                val applet = DXApplet.getInstance(dxExecId)
                val fields = Map(
                    "name" -> JsString(dbgName),
                    "input" -> callInputs,
                    "properties" -> JsObject("call" ->  JsString(callName),
                                             "seq_number" -> JsString(seqNum.toString))
                )
                // TODO: If this is a task that specifies the instance type
                // at runtime, launch it in the requested instance.
                //
                val req = JsObject(fields)
                val retval: JsonNode = DXAPI.appletRun(applet.getId,
                                                       Utils.jsonNodeOfJsValue(req),
                                                       classOf[JsonNode])
                val info: JsValue =  Utils.jsValueOfJsonNode(retval)
                val id:String = info.asJsObject.fields.get("id") match {
                    case Some(JsString(x)) => x
                    case _ => throw new AppInternalException(
                        s"Bad format returned from jobNew ${info.prettyPrint}")
                }
                DXJob.getInstance(id)
            } else if (dxExecId.startsWith("workflow-")) {
                val workflow = DXWorkflow.getInstance(dxExecId)
                val dxAnalysis :DXAnalysis = workflow.newRun()
                    .setRawInput(Utils.jsonNodeOfJsValue(callInputs))
                    .setName(dbgName)
                    .putProperty("call", callName)
                    .putProperty("seq_number", seqNum.toString)
                    .run()
                dxAnalysis
            } else {
                throw new Exception(s"Unsupported execution ${linkInfo.dxExec}")
            }
        (seqNum, dxExec)
    }

    // create promises to this call. This allows returning
    // from the parent job immediately.
    private def genPromisesForCall(call: CallNode,
                                   linkInfo: ExecLinkInfo,
                                   dxExec: DXExecution) : Map[String, WdlVarLinks] = {
        val callName = call.identifier.localName.value
        linkInfo.outputs.map{
            case (varName, womType) =>
                val oName = s"${callName}.${varName}"
                oName -> WdlVarLinks(womType,
                                     DxlExec(dxExec, varName))
        }.toMap
    }

    // This method is exposed so that we can unit-test it.
    def evalExpressions(nodes: Seq[GraphNode],
                        env: Map[String, WomValue]) : Map[String, WomValue] = {
/*        val dbgGraph = nodes.map{node => WomPrettyPrint.apply(node) }.mkString("  \n")
        Utils.appletLog(verbose,
                        s"""|--- evalExpressions
                            |env =
                            |   ${env.mkString("  \n")}
                            |graph =
                            |   ${dbgGraph}
                            |---
                            |""".stripMargin)*/
        nodes.foldLeft(env) {
            // simple expression
            case (env, eNode: ExpressionNode) =>
                val value : WomValue =
                    evaluateWomExpression(eNode.womExpression, eNode.womType, env)
                env + (eNode.identifier.workflowLocalName -> value)

            // scatter
            case(env, sctNode : ScatterNode) =>
                assert(sctNode.scatterVariableNodes.size == 1)
                val svNode: ScatterVariableNode = sctNode.scatterVariableNodes.head
                val collectionRaw : WomValue =
                    evaluateWomExpression(svNode.scatterExpressionNode.womExpression,
                                          WomArrayType(svNode.womType),
                                          env)
                val collection : Seq[WomValue] = collectionRaw match {
                    case x: WomArray => x.value
                    case other => throw new AppInternalException(
                        s"Unexpected class ${other.getClass}, ${other}")
                }
                // iterate on the collection
                val vm : Vector[Map[String, WomValue]] =
                    collection.map{ v =>
                        val envInner = env + (svNode.identifier.workflowLocalName -> v)
                        evalExpressions(sctNode.innerGraph.nodes.toSeq, envInner)
                    }.toVector

                val resultTypes : Map[String, WomArrayType] = sctNode.outputMapping.map{
                    case scp : ScatterGathererPort =>
                        scp.identifier.workflowLocalName -> scp.womType
                }.toMap

                // merge the vector of results, each of which is a map
                val initResults = resultTypes.map{
                    case (key, WomArrayType(elemType)) => key -> (elemType, Vector.empty[WomValue])
                    case (_, other) =>
                        throw new AppInternalException(
                            s"Unexpected class ${other.getClass}, ${other}")
                }.toMap
                val results : Map[String, (WomType, Vector[WomValue])] =
                    vm.foldLeft(initResults) {
                        case (accu, m) =>
                            accu.map{
                                case (key, (elemType, arValues)) =>
                                    val v : WomValue = m(key)
                                    val vCorrectlyTyped = elemType.coerceRawValue(v).get
                                    key -> (elemType, (arValues :+ vCorrectlyTyped))
                            }.toMap
                    }

                // Add the wom array type to each vector
                results.map{ case (key, (elemType, vv)) =>
                    key -> WomArray(WomArrayType(elemType), vv)
                }

            // Input nodes for a subgraph
            case (env, ogin: OuterGraphInputNode) =>
                //Utils.appletLog(verbose, s"skipping ${ogin.getClass}")
                env

            // Output nodes from a subgraph
            case (env, gon: GraphOutputNode) =>
                //Utils.appletLog(verbose, s"skipping ${gon.getClass}")
                env

            case (env, other) =>
                val dbgGraph = nodes.map{node => WomPrettyPrint.apply(node) }.mkString("\n")
                Utils.appletLog(true, s"""|Error unimplemented type ${other.getClass} while evaluating expressions
                                          |
                                          |env =
                                          |${env}
                                          |
                                          |graph =
                                          |${dbgGraph}
                                          |""".stripMargin)
                throw new Exception(s"${other.getClass} not implemented yet")
        }
    }


    def apply(subBlockNr: Int,
              env: Map[String, WomValue]) : Map[String, JsValue] = {
        Utils.appletLog(verbose, s"dxWDL version: ${Utils.getVersion()}")
        Utils.appletLog(verbose, s"link info=${execLinkInfo}")
        Utils.appletLog(verbose, s"Workflow source code:")
        Utils.appletLog(verbose, wfSourceCode, 10000)
        Utils.appletLog(verbose, s"Environment: ${env}")

        val (_, subBlocks, _) = Block.splitIntoBlocks(wf.innerGraph, wfSourceCode)
        val block = subBlocks(subBlockNr)

        Utils.appletLog(verbose, s"""|Block ${subBlockNr} to execute:
                                     |${block.prettyPrint}
                                     |""".stripMargin)

        // Split the block into expressions to evaluate followed by zero or
        // one calls to another applet/workflow.
        val calls: Vector[CallNode] = block.nodes.collect{
            case x:CallNode => x
        }.toVector
        val otherNodes: Vector[GraphNode] = block.nodes.filter{ x => !x.isInstanceOf[CallNode] }
        val callEnv = evalExpressions(otherNodes, env)
        val exportedVars = exportedVarNames()
        if (calls.isEmpty) {
            return processOutputs(callEnv, Map.empty, exportedVars)
        }
        if (calls.size > 1)
            throw new AppInternalException(
                s"""|There are ${calls.size} calls in the subblock.
                    |There can only be zero or one""".stripMargin.replaceAll("\n", " "))

        // There is a single call, and we need to process it
        val call = calls.head

        // find the callee
        val calleeName = call.callable.name
        val linkInfo = execLinkInfo.get(calleeName) match {
            case None =>
                throw new AppInternalException(
                    s"Could not find linking information for ${calleeName}")
            case Some(eInfo) => eInfo
        }

        val (_, dxExec) = execCall(call, callEnv, linkInfo, None)
        val callResults: Map[String, WdlVarLinks] = genPromisesForCall(call, linkInfo, dxExec)
        val callResultsDbgStr = callResults.mkString("\n")
        Utils.appletLog(verbose, s"""|promises to future values:
                                     |${callResultsDbgStr}""".stripMargin)

        val jsOutputs: Map[String, JsValue] = processOutputs(callEnv, callResults, exportedVars)
        val jsOutputsDbgStr = jsOutputs.mkString("\n")
        Utils.appletLog(verbose, s"""|JSON outputs:
                                     |${jsOutputsDbgStr}""".stripMargin)
        jsOutputs
    }
}
