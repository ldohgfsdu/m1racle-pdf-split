------------------------------- MODULE PdfSplitCleanup -------------------------------
EXTENDS TLC, Naturals, FiniteSets
CONSTANT Correct
VARIABLES phase, created, deleted
vars == <<phase, created, deleted>>
Init == /\ phase = "idle" /\ created = 0 /\ deleted = 0
Start == /\ phase = "idle" /\ phase' = "writing"
         /\ UNCHANGED <<created, deleted>>
Write == /\ phase = "writing" /\ created < 2
         /\ created' = created + 1 /\ UNCHANGED <<phase, deleted>>
Cancel == /\ phase = "writing" /\ phase' = "cleanup"
          /\ UNCHANGED <<created, deleted>>
Delete == /\ phase = "cleanup" /\ deleted < created
          /\ deleted' = deleted + 1 /\ UNCHANGED <<phase, created>>
DeleteFails == /\ phase = "cleanup" /\ deleted < created
               /\ phase' = IF Correct THEN "cleanupError" ELSE "cancelled"
               /\ UNCHANGED <<created, deleted>>
ConfirmCancel == /\ phase = "cleanup" /\ deleted = created
                 /\ phase' = "cancelled"
                 /\ UNCHANGED <<created, deleted>>
Complete == /\ phase = "writing" /\ created = 2
            /\ phase' = "done"
            /\ UNCHANGED <<created, deleted>>
Finish == /\ phase \in {"done", "cancelled", "cleanupError"} /\ UNCHANGED vars
Next == Start \/ Write \/ Cancel \/ Delete \/ DeleteFails \/ ConfirmCancel \/ Complete \/ Finish
Spec == Init /\ [][Next]_vars
CancelledNoResidue == phase # "cancelled" \/ created = deleted
DoneHasAllOutputs == phase # "done" \/ created = 2
=============================================================================
