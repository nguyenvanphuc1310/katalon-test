#!/usr/bin/env bash
# Run the keyword checks WITHOUT opening Katalon Studio — no browser, no VPN, a few seconds.
#
#   tools/offline-checks/run.sh              # compile + verdict/score rules + report contract
#   tools/offline-checks/run.sh replay       # ...and replay the snapshots already on disk
#
# Everything runs on Katalon's OWN Groovy and JRE. That is deliberate: the system `java` here is
# newer than Groovy 3 can read, while Katalon executes on 21 — the same version mismatch that
# produces the misleading `UnsupportedClassVersionError` inside Studio.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KATALON_APP="${KATALON_APP:-/Applications/Katalon Studio.app}"
ECLIPSE="$KATALON_APP/Contents/Eclipse"

JAVA="$ECLIPSE/jre/bin/java"
GROOVY_JAR="$(ls "$ECLIPSE"/plugins/org.codehaus.groovy_*/lib/groovy-*-indy.jar 2>/dev/null | grep -v test | head -1)"
# groovy.json lives only in the older bundled jar; the indy jar has GroovyMain but no json package
JSON_JAR="$ECLIPSE/configuration/resources/lib/groovy-3.0.17.jar"

[ -x "$JAVA" ] || { echo "Katalon JRE not found at: $JAVA (set KATALON_APP)" >&2; exit 1; }
[ -n "$GROOVY_JAR" ] || { echo "Groovy jar not found under $ECLIPSE/plugins" >&2; exit 1; }

# `|| true` so a classpath with no jars does not kill the script under `set -e` before it can say why.
PROJECT_CP="$(grep -oE 'path="[^"]*\.jar"' "$PROJECT_DIR/.classpath" | sed 's/path="//;s/"$//' | tr '\n' ':' || true)"
# Katalon's own .classpath lists every plugin jar. A Gradle/Buildship "refresh" replaces the whole
# file with a classpath container and leaves zero lib entries — then nothing resolves com.kms.katalon.*
# and Studio blames the keywords with "unable to resolve class migration.<X>".
[ -n "$PROJECT_CP" ] || {
	echo "ERROR: $PROJECT_DIR/.classpath has no jar entries." >&2
	echo "       Katalon's classpath was overwritten (Gradle/Buildship?). Close Katalon Studio," >&2
	echo "       delete .classpath .project .settings/org.eclipse.{buildship.core,jdt.core}.prefs .gradle bin," >&2
	echo "       then reopen the project so Studio regenerates it." >&2
	exit 1
}
CP="$GROOVY_JAR:$JSON_JAR:$PROJECT_CP$PROJECT_DIR/Keywords"

cd "$PROJECT_DIR"

echo "== compiling every keyword =="
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$JAVA" -cp "$CP" org.codehaus.groovy.tools.FileSystemCompiler -d "$OUT" $(find Keywords -name "*.groovy")
echo "   $(find "$OUT" -name '*.class' | wc -l | tr -d ' ') classes, no compilation errors"
# A Groovy-Eclipse error stub is a valid class file of ~800 bytes whose constructor throws, so
# "it compiled" is not the same as "it works" — flag anything suspiciously small.
find "$OUT" -name "*.class" -size -1k ! -name "*\$*" -print | while read -r f; do
	echo "   WARNING: ${f#$OUT/} is under 1 KB — check it is not a compiler error stub"
done
# One class file version for the whole tree. A single file rebuilt by an IDE at a different level
# is enough to throw UnsupportedClassVersionError at runtime, and it names the wrong culprit.
VER="$(find "$OUT" -name '*.class' ! -name '*$*' -print0 \
	| xargs -0 -n1 od -An -tu1 -j7 -N1 \
	| tr -d ' ' | grep -v '^$' | sort -u | tr '\n' ' ')"
echo "   class file major version(s): $VER"
if [ "$(echo $VER | wc -w | tr -d ' ')" != "1" ]; then
	echo "   WARNING: mixed class file versions — clear bin/keyword/ and .cache/Keywords/ before running in Studio"
fi

echo
echo "== verdict + score rules =="
"$JAVA" -cp "$CP" groovy.ui.GroovyMain tools/offline-checks/verdict-rules.groovy

echo
echo "== report contract =="
"$JAVA" -cp "$CP" groovy.ui.GroovyMain tools/offline-checks/report-contract.groovy

if [ "${1:-}" = "replay" ]; then
	echo
	echo "== replay over the snapshots on disk =="
	"$JAVA" -cp "$CP" groovy.ui.GroovyMain tools/offline-checks/replay-on-disk.groovy
fi
