------------------------------- MODULE PdfAutoParser -------------------------------
EXTENDS TLC, Naturals, FiniteSets
CONSTANT Correct
VARIABLES phase, trusted, complete, painted, chosen
vars == <<phase, trusted, complete, painted, chosen>>
Init == /\ phase = "new" /\ trusted = FALSE /\ complete = FALSE
        /\ painted = FALSE /\ chosen = "none"
Scan(t) == /\ phase = "new" /\ t \in BOOLEAN
           /\ trusted' = t /\ phase' = "scanned"
           /\ UNCHANGED <<complete, painted, chosen>>
Probe(c, p) == /\ phase = "scanned" /\ c \in BOOLEAN /\ p \in BOOLEAN
               /\ complete' = c /\ painted' = p /\ phase' = "probed"
               /\ UNCHANGED <<trusted, chosen>>
Choose == /\ phase = "probed"
          /\ chosen' = IF Correct
                        THEN (IF trusted /\ complete /\ ~painted THEN "strict" ELSE "compatible")
                        ELSE (IF trusted THEN "strict" ELSE "compatible")
          /\ phase' = "done"
          /\ UNCHANGED <<trusted, complete, painted>>
Finish == /\ phase = "done" /\ UNCHANGED vars
Next == (\E t \in BOOLEAN : Scan(t))
        \/ (\E c \in BOOLEAN : \E p \in BOOLEAN : Probe(c,p))
        \/ Choose \/ Finish
Spec == Init /\ [][Next]_vars
StrictNeedsEvidence == chosen # "strict" \/ (trusted /\ complete /\ ~painted)
=============================================================================
