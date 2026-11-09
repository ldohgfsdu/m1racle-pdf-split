------------------------------- MODULE PdfReleasePublication -------------------------------
EXTENDS TLC, Naturals, FiniteSets
CONSTANT Correct
VARIABLES phase, uploaded, verified, public
vars == <<phase, uploaded, verified, public>>
Init == /\ phase = "new" /\ uploaded = FALSE
        /\ verified = FALSE /\ public = FALSE
Build == /\ phase = "new" /\ phase' = "built"
         /\ UNCHANGED <<uploaded, verified, public>>
CreateDraft == /\ phase = "built" /\ phase' = "draft"
               /\ public' = ~Correct
               /\ UNCHANGED <<uploaded, verified>>
Upload == /\ phase = "draft" /\ uploaded' = TRUE
          /\ phase' = "uploaded" /\ UNCHANGED <<verified, public>>
Verify == /\ phase = "uploaded" /\ verified' = TRUE
          /\ phase' = "verified" /\ UNCHANGED <<uploaded, public>>
Publish == /\ phase = "verified" /\ public' = TRUE
           /\ phase' = "public" /\ UNCHANGED <<uploaded, verified>>
Fail == /\ phase \in {"built","draft","uploaded"}
        /\ phase' = "failed" /\ UNCHANGED <<uploaded, verified, public>>
Finish == /\ phase \in {"public", "failed"} /\ UNCHANGED vars
Next == Build \/ CreateDraft \/ Upload \/ Verify \/ Publish \/ Fail \/ Finish
Spec == Init /\ [][Next]_vars
PublicHasVerifiedAssets == ~public \/ (uploaded /\ verified)
=============================================================================
