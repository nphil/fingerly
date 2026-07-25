#!/usr/bin/env python3
"""Extract the ADHD/music/RL research workflow output into docs/RESEARCH-GAMIFICATION.md."""
import json, re, difflib

JOURNAL = ("/root/.claude/projects/-home-user-fingerly/"
           "9fd00246-7fc1-5da2-90b9-05cffe3cfc6a/subagents/workflows/"
           "wf_e58512a6-253/journal.jsonl")

rows = [json.loads(l) for l in open(JOURNAL)]
results = [d["result"] for d in rows if d.get("type") == "result"]

research = [r for r in results if isinstance(r, dict) and "findings" in r]
verdicts = [r for r in results if isinstance(r, dict) and "verified" in r]
plan = next((r for r in results if isinstance(r, dict) and "invisibleMechanics" in r), None)
critique = next((r for r in results if isinstance(r, str)), None)

# The verifier paraphrased claims, so match on citation (stable) and fall back
# to fuzzy claim similarity. Anything below the threshold stays UNCHECKED
# rather than being paired up optimistically.
all_verdicts = [item for v in verdicts for item in v["verified"]]


def norm(s):
    return re.sub(r"[^a-z0-9 ]", " ", (s or "").lower())


STOP = {"the", "and", "for", "with", "study", "journal", "review", "effects",
        "effect", "adhd", "adults", "children", "learning", "attention"}


def cite_tokens(s):
    """All (surname, year) pairs mentioned anywhere in a citation string."""
    s = s or ""
    years = set(re.findall(r"(?:19|20)\d{2}", s))
    names = {w.lower() for w in re.findall(r"\b[A-Z][a-z]{3,}\b", s)
             if w.lower() not in STOP}
    return names, years


def verdict_for(finding):
    fnames, fyears = cite_tokens(finding.get("citation"))
    # A shared surname AND a shared year is a confident citation match.
    for v in all_verdicts:
        vnames, vyears = cite_tokens(v.get("citation"))
        if (fnames & vnames) and (fyears & vyears):
            return v
    # Otherwise fall back to claim similarity, kept deliberately strict:
    # an unmatched finding stays UNCHECKED rather than borrowing a status.
    best, best_score = None, 0.0
    fc = norm(finding["claim"])
    for v in all_verdicts:
        score = difflib.SequenceMatcher(None, fc, norm(v["claim"])).ratio()
        if score > best_score:
            best, best_score = v, score
    return best if best_score >= 0.42 else None


def clean(s):
    if not s:
        return ""
    return re.sub(r"\s+", " ", str(s)).strip()


out = []
w = out.append

w("# Research Record — Gamification, game feel, and engagement mechanics")
w("")
w("Verified literature behind the learning engines. Generated from a "
  "multi-agent review (five literature areas researched in parallel, every "
  "claim then adversarially fact-checked by an independent reviewer that "
  "attempted to refute it).")
w("")
w("**How to read the status tags.** `CONFIRMED` = the citation exists and the "
  "claim represents it accurately. `MISCHARACTERIZED` = the paper is real but "
  "the claim overstated it — the correction is quoted. `UNVERIFIABLE` = the "
  "citation could not be confirmed; do not build on it. Evidence strength: "
  "`STRONG` = replicated or meta-analytic, `MODERATE` = one well-powered "
  "study, `WEAK` = small-n, children-only generalised to adults, or "
  "non-peer-reviewed.")
w("")
w("**Rule for using this file:** only `CONFIRMED` findings at `MODERATE` or "
  "`STRONG` may drive a parameter change. Everything else is context.")
w("")
w("See `docs/LEARNING.md` for the resulting architecture and the tunables "
  "each of these findings justifies.")
w("")
w("---")
w("")

for area in research:
    w(f"## {clean(area['area'])}")
    w("")
    for f in area["findings"]:
        v = verdict_for(f)
        status = f"{v['status']} · {v['strength']}" if v else "UNCHECKED"
        w(f"### {clean(f['claim'])}")
        w("")
        w(f"- **Status:** {status}")
        w(f"- **Citation:** {clean(f.get('citation'))}")
        if f.get("url"):
            w(f"- **URL:** {clean(f['url'])}")
        w(f"- **Peer reviewed:** {f.get('peerReviewed')}")
        w(f"- **Sample / design:** {clean(f.get('sampleAndDesign'))}")
        if f.get("effectSize"):
            w(f"- **Effect size:** {clean(f['effectSize'])}")
        if f.get("kind"):
            w(f"- **Kind:** {clean(f['kind'])}")
        if f.get("evidenceType"):
            w(f"- **Evidence type:** {clean(f['evidenceType'])}")
        if f.get("ethicalStatus"):
            w(f"- **Ethical status:** {clean(f['ethicalStatus'])}")
        if f.get("concreteExample"):
            w(f"- **Concrete example:** {clean(f['concreteExample'])}")
        if f.get("whyItWorks"):
            w(f"- **Mechanism:** {clean(f['whyItWorks'])}")
        if f.get("pianoAppApplication"):
            w(f"- **Piano-app application:** {clean(f['pianoAppApplication'])}")
        if f.get("designImplication"):
            w(f"- **Design implication:** {clean(f.get('designImplication'))}")
        if v and v.get("correction"):
            w(f"- **Verifier correction:** {clean(v['correction'])}")
        if v and v.get("replicationNotes"):
            w(f"- **Replication:** {clean(v['replicationNotes'])}")
        if v and v.get("notes"):
            w(f"- **Verifier notes:** {clean(v['notes'])}")
        w("")
    w("---")
    w("")

if plan:
    w("## Highest-leverage conclusion")
    w("")
    w(clean(plan.get("highestLeverage")))
    w("")
    for rec in plan.get("reconciliations", []):
        w(f"- **Tension:** {clean(rec.get('tension'))}")
        w(f"  - Resolution: {clean(rec.get('resolution'))}")
    w("")
    w("## Changes this evidence produced")
    w("")
    for i in plan.get("keyInsights", []):
        w(f"- {clean(i)}")
    w("")
    for c in (plan.get("invisibleMechanics", []) + plan.get("visibleUi", [])):
        w(f"### [{c.get('priority','?')}] {clean(c.get('title'))}")
        w("")
        w(f"- **Change:** {clean(c.get('whatToChange'))}")
        if c.get("adhdAdaptation"):
            w(f"- **ADHD adaptation:** {clean(c['adhdAdaptation'])}")
        w(f"- **Rationale:** {clean(c.get('rationale'))}")
        if c.get("citation"):
            w(f"- **Citation:** {clean(c['citation'])}")
        if c.get("learnerFit"):
            w(f"- **Learner fit:** {clean(c['learnerFit'])}")
        if c.get("risk"):
            w(f"- **Risk:** {clean(c['risk'])}")
        w("")
    w("---")
    w("")
    w("## Ideas rejected on evidence")
    w("")
    w("Kept deliberately: re-proposing these without new evidence is a "
      "regression, not an improvement.")
    w("")
    for r in plan.get("rejected", []):
        w(f"### {clean(r.get('idea'))}")
        w("")
        w(clean(r.get("whyRejected")))
        w("")
    w("---")
    w("")

if critique:
    w("## Adversarial critique of the change plan")
    w("")
    w("An independent critic reviewing the plan for gaps, over-engineering, "
      "backfires and internal contradictions. Retained because several points "
      "are still open work.")
    w("")
    w(critique)
    w("")

open("/home/user/fingerly/docs/RESEARCH-GAMIFICATION.md", "w").write("\n".join(out) + "\n")
n_findings = sum(len(a["findings"]) for a in research)
n_verified = len(all_verdicts)
print(f"findings={n_findings} verdicts={n_verified} "
      f"changes={len(plan.get('changes', [])) if plan else 0} "
      f"rejected={len(plan.get('rejected', [])) if plan else 0}")
