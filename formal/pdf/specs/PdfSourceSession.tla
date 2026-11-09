------------------------------- MODULE PdfSourceSession -------------------------------
EXTENDS TLC, Naturals, FiniteSets
CONSTANT Correct
VARIABLES generation, pending, visible, rules
vars == <<generation, pending, visible, rules>>
Init == /\ generation = 0 /\ pending = {} /\ visible = 0 /\ rules = 0
Choose == /\ generation < 2
          /\ generation' = generation + 1
          /\ pending' = pending \cup {generation + 1}
          /\ visible' = 0 /\ rules' = 0
Complete(g) ==
    /\ g \in pending
    /\ pending' = pending \ {g}
    /\ visible' = IF Correct /\ g # generation THEN visible ELSE g
    /\ rules' = IF Correct /\ g # generation THEN rules ELSE g
    /\ UNCHANGED generation
Finish == /\ generation = 2 /\ pending = {} /\ UNCHANGED vars
Next == Choose \/ (\E g \in pending : Complete(g)) \/ Finish
Spec == Init /\ [][Next]_vars
NoStaleSource == visible = 0 \/ visible = generation
RulesBoundToSource == visible = 0 \/ visible = rules
=============================================================================
