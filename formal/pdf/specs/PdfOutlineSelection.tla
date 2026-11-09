------------------------------- MODULE PdfOutlineSelection -------------------------------
EXTENDS TLC, Naturals, FiniteSets
CONSTANT Correct
VARIABLES selected, planned, ranges
vars == <<selected, planned, ranges>>
Init == /\ selected = {} /\ planned = FALSE /\ ranges = {}
OwnedEnd(i) == IF i = 1 THEN 5 ELSE IF i = 2 THEN 11 ELSE 20
Select(i) == /\ ~planned /\ i \in 1..3 /\ i \notin selected
             /\ selected' = selected \cup {i}
             /\ UNCHANGED <<planned, ranges>>
Build == /\ ~planned /\ selected # {}
         /\ planned' = TRUE
         /\ ranges' = {<<i, IF Correct THEN OwnedEnd(i) ELSE 20>> : i \in selected}
         /\ UNCHANGED selected
Finish == /\ planned /\ UNCHANGED vars
Next == (\E i \in 1..3 : Select(i)) \/ Build \/ Finish
Spec == Init /\ [][Next]_vars
SelectedOnly == \A r \in ranges : r[1] \in selected
CorrectEndPage == \A r \in ranges : r[2] <= OwnedEnd(r[1])
=============================================================================
