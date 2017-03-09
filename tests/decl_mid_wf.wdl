# Workflow with declarations in the middle that can be lifted to the top level.
task add {
    Int a
    Int b

    command {
        python -c "print(${a} + ${b})"
    }
    output {
        Int sum = read_int(stdout())
    }
}


task concat {
    String s1
    String s2

    command {
        echo "${s1}_${s2}"
    }
    output {
        String result = read_string(stdout())
    }
}

workflow decl_mid_wf {
    String s
    Int i

    call add {
        input: a = (i * 2), b = (i+3)
    }

    String q = sub(s, "frogs", "RIP")
    Int j = i + i

    call concat {
        input:
                s1 = s + ".aligned",
                s2 = q + ".wgs"
    }

    Int k = 2 * j

    call add as add2 {
        input: a = j, b = k
    }

    output {
        add.sum
        add2.sum
        concat.result
    }
}