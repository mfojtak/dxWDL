package dxWDL.compiler

import dxWDL.{CompilerErrorFormatter, Verbose}
import wdl.draft2.model._

import org.scalatest.{FlatSpec, Matchers}

class VarAnalysisTest extends FlatSpec with Matchers {
    it should "identify variables inside interpolations" in {
        val wdlCode = """|
                         |workflow strings2 {
                         |    String s
                         |
                         |    # The following two calls are equivalent to
                         |    # concatenating three variables.
                         |    # Test string interpolation.
                         |    call concat {
                         |        input:
                         |            x = "${s}.aligned",
                         |            y = "${s}.duplicate_metrics"
                         |    }
                         |}
                         |
                         |# Concatenate two string
                         |task concat {
                         |    String x
                         |    String y
                         |
                         |    command {
                         |        echo ${x}_${y}
                         |    }
                         |    output {
                         |        String result = read_string(stdout())
                         |    }
                         |}
                         |""".stripMargin

        val ns = WdlNamespaceWithWorkflow.load(wdlCode, Seq.empty).get
        val wf = ns.asInstanceOf[WdlNamespaceWithWorkflow].workflow
        val verbose = Verbose(false, false, Set.empty)
        val cef = CompilerErrorFormatter(wf.unqualifiedName, ns.terminalMap)
        val va = new VarAnalysis(Set.empty, Map.empty, cef, verbose)

        val concatCall = wf.children(1)
        val freeVars = va.findAll(concatCall)

        freeVars should equal(Set("s"))
    }

    it should "Distinguish variables from stdlib functions" in {
        val wdlCode = """|
                         |workflow w {
                         |    File f_in
                         |    String s_in
                         |
                         |    Float len = size(f_in)
                         |    String s = sub(s_in, "dog", "cat")
                         |}
                         |""".stripMargin

        val ns = WdlNamespaceWithWorkflow.load(wdlCode, Seq.empty).get
        val wf = ns.asInstanceOf[WdlNamespaceWithWorkflow].workflow
        val verbose = Verbose(false, false, Set.empty)
        val cef = CompilerErrorFormatter(wf.unqualifiedName, ns.terminalMap)
        val va = new VarAnalysis(Set.empty, Map.empty, cef, verbose)

        def validate_decl(unqualifiedName: String,
                          expected: Set[String]) : Unit = {
            val decl = wf.children.find{
                case decl: Declaration =>
                    decl.unqualifiedName == unqualifiedName
                case _ => false
            }.get.asInstanceOf[Declaration]

            val freeVars = va.findAll(decl)
            freeVars should equal(expected)
        }

        validate_decl("len", Set("f_in"))
        validate_decl("s", Set("s_in"))
    }
}
