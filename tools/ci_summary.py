"""Writes test counts and coverage to the GitHub Actions job summary (and the log)."""
import glob, os, sys
import xml.etree.ElementTree as ET

title = sys.argv[1]
lines = [f"### {title}", ""]

tests = failures = skipped = 0
for path in glob.glob("**/build/test-results/**/*.xml", recursive=True) + glob.glob("**/build/outputs/androidTest-results/**/*.xml", recursive=True):
    root = ET.parse(path).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    for s in suites:
        tests += int(s.get("tests", 0))
        failures += int(s.get("failures", 0)) + int(s.get("errors", 0))
        skipped += int(s.get("skipped", 0))
lines.append(f"- Tests: **{tests - failures - skipped} passed**, {failures} failed, {skipped} skipped")

for path in glob.glob("**/build/reports/jacoco/test/jacocoTestReport.xml", recursive=True):
    root = ET.parse(path).getroot()
    for c in root.findall("counter"):
        if c.get("type") == "LINE":
            covered, missed = int(c.get("covered")), int(c.get("missed"))
            lines.append(f"- Line coverage ({path.split('/')[0]}): **{100 * covered / (covered + missed):.1f}%** ({covered}/{covered + missed} lines)")

text = "\n".join(lines) + "\n"
print(text)
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a") as f:
        f.write(text)
