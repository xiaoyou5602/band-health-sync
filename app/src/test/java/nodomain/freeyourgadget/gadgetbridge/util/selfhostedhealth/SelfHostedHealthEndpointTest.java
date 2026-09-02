package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The address field is the one place a user can silently get this wrong: typing the domain and
 * nothing else posts to "/", which the server answers with an HTML 404 that looks like an outage.
 */
public class SelfHostedHealthEndpointTest {

    @Test
    public void bareDomainGetsTheIngestPath() {
        assertEquals("https://health.example.com/api/health",
                SelfHostedHealthEndpoint.normalize("health.example.com"));
    }

    @Test
    public void originWithSchemeGetsTheIngestPath() {
        assertEquals("https://health.example.com/api/health",
                SelfHostedHealthEndpoint.normalize("https://health.example.com"));
    }

    @Test
    public void trailingSlashIsTreatedAsAnOrigin() {
        assertEquals("https://health.example.com/api/health",
                SelfHostedHealthEndpoint.normalize("https://health.example.com/"));
    }

    @Test
    public void aFullEndpointIsLeftAlone() {
        assertEquals("https://health.example.com/api/health",
                SelfHostedHealthEndpoint.normalize("https://health.example.com/api/health"));
    }

    /** Someone hosting the service under a different path must not have it rewritten away. */
    @Test
    public void anExplicitPathIsPreserved() {
        assertEquals("https://example.com/gadget/ingest",
                SelfHostedHealthEndpoint.normalize("https://example.com/gadget/ingest"));
    }

    @Test
    public void portsAndHttpSurvive() {
        assertEquals("http://192.168.1.10:3100/api/health",
                SelfHostedHealthEndpoint.normalize("http://192.168.1.10:3100"));
    }

    @Test
    public void surroundingWhitespaceIsIgnored() {
        assertEquals("https://health.example.com/api/health",
                SelfHostedHealthEndpoint.normalize("  health.example.com  "));
    }

    @Test
    public void unusableInputIsRejected() {
        assertNull(SelfHostedHealthEndpoint.normalize(null));
        assertNull(SelfHostedHealthEndpoint.normalize(""));
        assertNull(SelfHostedHealthEndpoint.normalize("   "));
        assertNull(SelfHostedHealthEndpoint.normalize("ftp://health.example.com"));
    }

    /** The status line has one line to explain itself; raw markup would spend it all. */
    @Test
    public void htmlErrorPagesAreReducedToTheirSentence() {
        String expressNotFound = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<title>Error</title>\n</head>\n<body>\n<pre>Cannot POST /</pre>\n</body>\n</html>\n";
        assertEquals("Error Cannot POST /",
                SelfHostedHealthUploader.summarizeErrorBody(expressNotFound));
    }

    @Test
    public void jsonErrorBodiesArePassedThrough() {
        assertEquals("{\"error\":\"Unauthorized\"}",
                SelfHostedHealthUploader.summarizeErrorBody("{\"error\":\"Unauthorized\"}"));
    }

    /**
     * The reported failure: a 64-char token pasted from a wrapped copy arrived split by a newline in
     * the middle. That control char makes OkHttp refuse to build the request, so the upload died with
     * no status shown. Stripping it must rejoin the halves into the original token.
     */
    @Test
    public void tokenNewlineInTheMiddleIsRemoved() {
        assertEquals("a98ebdbd27f05ce3681a22d0b778011f7e110c27f852ad73d0595622b652341a",
                SelfHostedHealthUploader.sanitizeToken(
                        "a98ebdbd27f05ce3681a22d0b778011f7e110c27f8\n52ad73d0595622b652341a"));
    }

    @Test
    public void tokenSurroundingWhitespaceIsRemoved() {
        assertEquals("secrettoken", SelfHostedHealthUploader.sanitizeToken("  secrettoken\n"));
    }

    @Test
    public void aCleanTokenIsLeftAlone() {
        assertEquals("secrettoken", SelfHostedHealthUploader.sanitizeToken("secrettoken"));
    }

    @Test
    public void anAbsentTokenSanitizesToEmpty() {
        assertEquals("", SelfHostedHealthUploader.sanitizeToken(null));
        assertEquals("", SelfHostedHealthUploader.sanitizeToken("   \n\t "));
    }
}
