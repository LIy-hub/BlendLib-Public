import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/** Checks the packaged declarations using Fabric Loader's real version predicate parser. */
public final class VerifyDependencyRanges {
    public static void main(String[] jars) throws Exception {
        if (jars.length == 0) throw new IllegalArgumentException("Supply the runtime JAR paths");
        for (String jar : jars) {
            String json;
            try (var zip = new ZipFile(jar)) {
                json = new String(zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String dependency : new String[] {"fabricloader", "fabric-api"}) {
                var match = Pattern.compile("\"" + dependency + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                if (!match.find()) throw new AssertionError("Missing " + dependency + " in " + jar);
                String range = match.group(1);
                if (!range.startsWith(">=")) throw new AssertionError("Exact dependency lock: " + range);
                String minimum = range.substring(2);
                String[] parts = minimum.split("\\+", 2);
                String[] numbers = parts[0].split("\\.");
                int patch = Integer.parseInt(numbers[2]);
                String suffix = parts.length == 2 ? "+" + parts[1] : "";
                String newer = numbers[0] + "." + numbers[1] + "." + (patch + 1) + suffix;
                String older = patch > 0
                        ? numbers[0] + "." + numbers[1] + "." + (patch - 1) + suffix
                        : numbers[0] + "." + (Integer.parseInt(numbers[1]) - 1) + ".999" + suffix;
                var predicate = VersionPredicate.parse(range);
                if (!predicate.test(Version.parse(minimum)) || !predicate.test(Version.parse(newer))
                        || predicate.test(Version.parse(older))) {
                    throw new AssertionError("Incorrect upgrade boundary: " + jar + " " + dependency + " " + range);
                }
            }
            System.out.println("PASS minimum/newer/older Fabric dependency boundaries: " + Path.of(jar).getFileName());
        }
    }
}
