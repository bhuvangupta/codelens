package com.codelens.gate;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Trivy downloads parent POMs and dependencies from every Maven repository a pom.xml
 * declares. On a pull request that URL is attacker-controlled, so an unchecked scan
 * lets a PR make this server send requests to any address, internal ones included.
 *
 * This guard reads the pom.xml files before Trivy does and reports every declared
 * repository whose host is not allowlisted.
 */
final class PomRepositoryGuard {

    private static final int MAX_POMS = 2000;
    private static final long MAX_POM_BYTES = 5L * 1024 * 1024;

    private PomRepositoryGuard() {}

    record Violation(String pomPath, String repositoryUrl) {}

    /**
     * @throws IOException if a pom.xml cannot be read or parsed. Trivy silently skips such
     *                     files, which would turn a broken pom into a clean scan.
     */
    static List<Violation> check(Path repoDir, Set<String> allowedHosts) throws IOException {
        List<Path> poms;
        try (Stream<Path> files = Files.walk(repoDir)) {
            poms = files
                .filter(path -> !repoDir.relativize(path).startsWith(".git"))
                .filter(path -> path.getFileName().toString().equals("pom.xml"))
                .filter(Files::isRegularFile)
                .limit(MAX_POMS + 1)
                .sorted()   // stable order for the report
                .toList();
        }
        if (poms.size() > MAX_POMS) {
            throw new IOException("More than " + MAX_POMS + " pom.xml files, refusing to scan");
        }

        List<Violation> violations = new ArrayList<>();
        for (Path pom : poms) {
            String relative = repoDir.relativize(pom).toString();
            if (Files.size(pom) > MAX_POM_BYTES) {
                throw new IOException(relative + " is too large to check");
            }
            Document document;
            try {
                document = newBuilder().parse(pom.toFile());
            } catch (Exception e) {
                throw new IOException(relative + " is not valid XML: " + e.getMessage(), e);
            }
            for (String tag : List.of("repository", "pluginRepository")) {
                NodeList nodes = document.getElementsByTagName(tag);
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element repository = (Element) nodes.item(i);
                    // <distributionManagement> is where artifacts get published, not fetched from
                    if (hasAncestor(repository, "distributionManagement")) {
                        continue;
                    }
                    String url = childText(repository, "url");
                    if (url != null && !allowedHosts.contains(hostOf(url))) {
                        violations.add(new Violation(relative, url));
                    }
                }
            }
        }
        return violations;
    }

    /** Lower-case host, or "" when it cannot be determined (e.g. the URL is a ${property}). */
    static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean hasAncestor(Node node, String name) {
        for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (name.equals(parent.getNodeName())) {
                return true;
            }
        }
        return false;
    }

    private static String childText(Element element, String name) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (name.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static DocumentBuilder newBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // The pom comes from an untrusted PR: no DTDs, no external entities
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null); // do not print parse errors to stderr
        return builder;
    }
}
