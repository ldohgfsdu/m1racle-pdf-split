------------------------------- MODULE PdfOutputNaming -------------------------------
EXTENDS TLC, Naturals, FiniteSets
CONSTANT Correct
VARIABLES phase, listingOk, actual, displayed
vars == <<phase, listingOk, actual, displayed>>
Init == /\ phase = "new" /\ listingOk = FALSE
        /\ actual = "" /\ displayed = ""
ListOk == /\ phase = "new" /\ phase' = "planned" /\ listingOk' = TRUE
          /\ UNCHANGED <<actual, displayed>>
ListFails == /\ phase = "new"
             /\ phase' = IF Correct THEN "blocked" ELSE "planned"
             /\ listingOk' = FALSE /\ UNCHANGED <<actual, displayed>>
Write(renamed) == /\ phase = "planned" /\ renamed \in BOOLEAN
                  /\ phase' = "shown"
                  /\ actual' = IF renamed THEN "chapter (1).pdf" ELSE "chapter.pdf"
                  /\ displayed' = IF Correct THEN (IF renamed THEN "chapter (1).pdf" ELSE "chapter.pdf") ELSE "chapter.pdf"
                  /\ UNCHANGED listingOk
Finish == /\ phase \in {"shown", "blocked"} /\ UNCHANGED vars
Next == ListOk \/ ListFails \/ (\E renamed \in BOOLEAN : Write(renamed)) \/ Finish
Spec == Init /\ [][Next]_vars
NoWriteWhenListFailed == phase # "shown" \/ listingOk
DisplayedNameMatchesActual == phase # "shown" \/ displayed = actual
=============================================================================
