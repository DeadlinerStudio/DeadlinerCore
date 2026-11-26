import java.nio.file.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Read-only validation of complete JUnit results and same-process Native events. */
public class VerifyNativeResults {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: VerifyNativeResults.java xml <dir> | events <log> <expected>");
        if (args[0].equals("xml")) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            long tests = 0, failures = 0, errors = 0, skipped = 0;
            try (var files = Files.list(Path.of(args[1]))) {
                for (Path file : files.filter(p -> p.getFileName().toString().startsWith("TEST-") &&
                    p.toString().endsWith(".xml")).toList()) {
                    var root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
                    tests += Long.parseLong(root.getAttribute("tests"));
                    failures += Long.parseLong(root.getAttribute("failures"));
                    errors += Long.parseLong(root.getAttribute("errors"));
                    skipped += Long.parseLong(root.getAttribute("skipped"));
                }
            }
            if (tests == 0 || failures != 0 || errors != 0 || skipped != 0)
                throw new IllegalStateException("Incomplete Native regression: tests=" + tests + ", failures=" + failures +
                    ", errors=" + errors + ", skipped=" + skipped);
            System.out.println(tests);
        } else if (args[0].equals("events") && args.length == 3) {
            long started = 0, finished = 0, failed = 0, ignored = 0;
            try (var lines = Files.lines(Path.of(args[1]))) {
                for (String line : lines.toList()) {
                    if (line.startsWith("##teamcity[testStarted ")) started++;
                    if (line.startsWith("##teamcity[testFinished ")) finished++;
                    if (line.startsWith("##teamcity[testFailed ")) failed++;
                    if (line.startsWith("##teamcity[testIgnored ")) ignored++;
                }
            }
            long expected = Long.parseLong(args[2]);
            if (started != expected || finished != expected || failed != 0 || ignored != 0)
                throw new IllegalStateException("Incomplete stress run: started=" + started + ", finished=" + finished +
                    ", failed=" + failed + ", ignored=" + ignored + ", expected=" + expected);
            System.out.println("Same-process full regression: " + finished + " passed, zero failed/ignored");
        } else throw new IllegalArgumentException("Unknown verification mode");
    }
}
