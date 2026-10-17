// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import io.github.eduardoalecrim.idempotency.OversizedPolicy;
import io.github.eduardoalecrim.idempotency.StoreFailurePolicy;
import io.github.eduardoalecrim.idempotency.key.KeyFormat;
import io.github.eduardoalecrim.idempotency.key.KeyParsing;
import io.github.eduardoalecrim.idempotency.problem.ProblemFactory;
import io.github.eduardoalecrim.idempotency.store.memory.InMemoryIdempotencyStore;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/** Configuration of the idempotency adapter, under {@code idempotency.*}. */
@ConfigurationProperties("idempotency")
public class IdempotencyProperties {

    /** Whether the adapter is enabled. Turns it off without removing the dependency. */
    private boolean enabled = true;

    /** Request header carrying the idempotency key. */
    private String headerName = "Idempotency-Key";

    /** HTTP methods that can be idempotent. POST, PATCH, PUT and DELETE are allowed. */
    private List<String> methods = new ArrayList<>(List.of("POST", "PATCH"));

    /** How long completed records are replayed, unless an operation sets its own TTL. */
    private Duration defaultTtl = Duration.ofHours(24);

    /**
     * How long an in-progress record protects a running request, unless an operation sets its own lease. Must be
     * shorter than the TTL of every operation.
     */
    private Duration lease = Duration.ofSeconds(30);

    private final Key key = new Key();

    private final ClientIdentity clientIdentity = new ClientIdentity();

    private final Fingerprint fingerprint = new Fingerprint();

    private final Request request = new Request();

    private final Response response = new Response();

    private final Problems problems = new Problems();

    private final Web web = new Web();

    private final Store store = new Store();

    /** Idempotent operations declared without annotations, for code the application does not own. */
    private List<Route> routes = new ArrayList<>();

    /** Returns whether the adapter is enabled. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Sets whether the adapter is enabled. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Returns the key header name. */
    public String getHeaderName() {
        return headerName;
    }

    /** Sets the key header name. */
    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    /** Returns the methods that can be idempotent. */
    public List<String> getMethods() {
        return methods;
    }

    /** Sets the methods that can be idempotent. */
    public void setMethods(List<String> methods) {
        this.methods = methods;
    }

    /** Returns the default TTL. */
    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    /** Sets the default TTL. */
    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    /** Returns the default lease. */
    public Duration getLease() {
        return lease;
    }

    /** Sets the default lease. */
    public void setLease(Duration lease) {
        this.lease = lease;
    }

    /** Returns the key settings. */
    public Key getKey() {
        return key;
    }

    /** Returns the client identity settings. */
    public ClientIdentity getClientIdentity() {
        return clientIdentity;
    }

    /** Returns the fingerprint settings. */
    public Fingerprint getFingerprint() {
        return fingerprint;
    }

    /** Returns the request settings. */
    public Request getRequest() {
        return request;
    }

    /** Returns the response settings. */
    public Response getResponse() {
        return response;
    }

    /** Returns the problem settings. */
    public Problems getProblems() {
        return problems;
    }

    /** Returns the web settings. */
    public Web getWeb() {
        return web;
    }

    /** Returns the store settings. */
    public Store getStore() {
        return store;
    }

    /** Returns the configured routes. */
    public List<Route> getRoutes() {
        return routes;
    }

    /** Sets the configured routes. */
    public void setRoutes(List<Route> routes) {
        this.routes = routes;
    }

    /** Key parsing settings. */
    public static class Key {

        /** Accepted header syntax: strict accepts only quoted strings, lenient also bare tokens such as UUIDs. */
        private KeyParsing parsing = KeyParsing.STRICT;

        /** Maximum key length after unescaping. */
        private int maxLength = 255;

        /** Accepted key values: any, or only canonical UUIDs. */
        private KeyFormat format = KeyFormat.ANY;

        /** Returns the header syntax. */
        public KeyParsing getParsing() {
            return parsing;
        }

        /** Sets the header syntax. */
        public void setParsing(KeyParsing parsing) {
            this.parsing = parsing;
        }

        /** Returns the maximum key length. */
        public int getMaxLength() {
            return maxLength;
        }

        /** Sets the maximum key length. */
        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }

        /** Returns the key format. */
        public KeyFormat getFormat() {
            return format;
        }

        /** Sets the key format. */
        public void setFormat(KeyFormat format) {
            this.format = format;
        }
    }

    /** Client identity settings. */
    public static class ClientIdentity {

        /** Where the client identity comes from: the authenticated principal, a request header, or nowhere. */
        private Source source = Source.PRINCIPAL;

        /** Request header holding the client identity when the source is header. */
        private @Nullable String header;

        /** Returns the source. */
        public Source getSource() {
            return source;
        }

        /** Sets the source. */
        public void setSource(Source source) {
            this.source = source;
        }

        /** Returns the header name. */
        public @Nullable String getHeader() {
            return header;
        }

        /** Sets the header name. */
        public void setHeader(@Nullable String header) {
            this.header = header;
        }

        /** Where the client identity comes from. */
        public enum Source {
            /** The authenticated principal. */
            PRINCIPAL,
            /** A request header. */
            HEADER,
            /** No identity: every request belongs to the anonymous client. */
            NONE
        }
    }

    /** Fingerprint settings. */
    public static class Fingerprint {

        /** Request headers added to every fingerprint. */
        private List<String> headers = new ArrayList<>();

        /** Whether JSON bodies are canonicalized with RFC 8785 before hashing. */
        private boolean canonicalizeJson = true;

        /** Returns the fingerprint headers. */
        public List<String> getHeaders() {
            return headers;
        }

        /** Sets the fingerprint headers. */
        public void setHeaders(List<String> headers) {
            this.headers = headers;
        }

        /** Returns whether JSON is canonicalized. */
        public boolean isCanonicalizeJson() {
            return canonicalizeJson;
        }

        /** Sets whether JSON is canonicalized. */
        public void setCanonicalizeJson(boolean canonicalizeJson) {
            this.canonicalizeJson = canonicalizeJson;
        }
    }

    /** Request settings. */
    public static class Request {

        /** Largest request body of an idempotent operation; larger bodies answer 413. */
        private DataSize maxBodySize = DataSize.ofMegabytes(1);

        /** Returns the request body limit. */
        public DataSize getMaxBodySize() {
            return maxBodySize;
        }

        /** Sets the request body limit. */
        public void setMaxBodySize(DataSize maxBodySize) {
            this.maxBodySize = maxBodySize;
        }
    }

    /** Response settings. */
    public static class Response {

        /** Largest response body that is stored; larger bodies follow the oversized policy. */
        private DataSize maxBodySize = DataSize.ofMegabytes(1);

        /** What happens to a larger response: store it without its body, or release the record. */
        private OversizedPolicy oversized = OversizedPolicy.STORE_WITHOUT_BODY;

        /** Status classes (2xx) or codes (201) that are stored. 5xx cannot be stored. */
        private List<String> storeStatuses = new ArrayList<>(List.of("2xx", "4xx"));

        /** Status codes or classes that release the record even when their class is stored. */
        private List<String> releaseStatuses = new ArrayList<>(List.of("408", "425", "429"));

        /** Response headers stored and restored on replay. Credentials and hop-by-hop headers are not allowed. */
        private List<String> replayHeaders = new ArrayList<>(
                List.of("Content-Type", "Content-Language", "Location", "ETag", "Last-Modified", "Cache-Control"));

        /** Returns the response body limit. */
        public DataSize getMaxBodySize() {
            return maxBodySize;
        }

        /** Sets the response body limit. */
        public void setMaxBodySize(DataSize maxBodySize) {
            this.maxBodySize = maxBodySize;
        }

        /** Returns the oversized policy. */
        public OversizedPolicy getOversized() {
            return oversized;
        }

        /** Sets the oversized policy. */
        public void setOversized(OversizedPolicy oversized) {
            this.oversized = oversized;
        }

        /** Returns the stored statuses. */
        public List<String> getStoreStatuses() {
            return storeStatuses;
        }

        /** Sets the stored statuses. */
        public void setStoreStatuses(List<String> storeStatuses) {
            this.storeStatuses = storeStatuses;
        }

        /** Returns the released statuses. */
        public List<String> getReleaseStatuses() {
            return releaseStatuses;
        }

        /** Sets the released statuses. */
        public void setReleaseStatuses(List<String> releaseStatuses) {
            this.releaseStatuses = releaseStatuses;
        }

        /** Returns the replayed headers. */
        public List<String> getReplayHeaders() {
            return replayHeaders;
        }

        /** Sets the replayed headers. */
        public void setReplayHeaders(List<String> replayHeaders) {
            this.replayHeaders = replayHeaders;
        }
    }

    /** Problem details settings. */
    public static class Problems {

        /** Base of problem type URIs; each problem type resolves to a page under it. */
        private URI baseUri = ProblemFactory.DEFAULT_BASE_URI;

        /** Returns the base URI. */
        public URI getBaseUri() {
            return baseUri;
        }

        /** Sets the base URI. */
        public void setBaseUri(URI baseUri) {
            this.baseUri = baseUri;
        }
    }

    /** Web adapter settings. */
    public static class Web {

        /** Order of the idempotency filter. The default runs just after the Spring Security filter chain (-100). */
        private int filterOrder = -90;

        /** Returns the filter order. */
        public int getFilterOrder() {
            return filterOrder;
        }

        /** Sets the filter order. */
        public void setFilterOrder(int filterOrder) {
            this.filterOrder = filterOrder;
        }
    }

    /** Store settings. */
    public static class Store {

        /** Which store to use; auto prefers an application bean, then JDBC, then Redis, then in-memory. */
        private StoreType type = StoreType.AUTO;

        /** What happens when the store fails before the handler: answer 503, or run the handler unprotected. */
        private StoreFailurePolicy onFailure = StoreFailurePolicy.FAIL_CLOSED;

        /** Timeout of a single store call. */
        private Duration timeout = Duration.ofSeconds(2);

        private final Jdbc jdbc = new Jdbc();

        private final InMemory inMemory = new InMemory();

        /** Returns the store type. */
        public StoreType getType() {
            return type;
        }

        /** Sets the store type. */
        public void setType(StoreType type) {
            this.type = type;
        }

        /** Returns the failure policy. */
        public StoreFailurePolicy getOnFailure() {
            return onFailure;
        }

        /** Sets the failure policy. */
        public void setOnFailure(StoreFailurePolicy onFailure) {
            this.onFailure = onFailure;
        }

        /** Returns the timeout of a store call. */
        public Duration getTimeout() {
            return timeout;
        }

        /** Sets the timeout of a store call. */
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        /** Returns the JDBC store settings. */
        public Jdbc getJdbc() {
            return jdbc;
        }

        /** Returns the in-memory store settings. */
        public InMemory getInMemory() {
            return inMemory;
        }

        /** JDBC store settings. */
        public static class Jdbc {

            /** Record table, optionally schema-qualified; must match {@code [A-Za-z_][A-Za-z0-9_.]*}. */
            private String tableName = "idempotency_record";

            /** Database dialect; auto detects it from the connection. */
            private JdbcDialectName dialect = JdbcDialectName.AUTO;

            /** Whether startup fails when the table or one of its columns is missing. */
            private boolean validateSchema = true;

            /** When to create the table with the shipped {@code schema-{vendor}.sql}. */
            private InitializeSchema initializeSchema = InitializeSchema.NEVER;

            /** Returns the table name. */
            public String getTableName() {
                return tableName;
            }

            /** Sets the table name. */
            public void setTableName(String tableName) {
                this.tableName = tableName;
            }

            /** Returns the dialect. */
            public JdbcDialectName getDialect() {
                return dialect;
            }

            /** Sets the dialect. */
            public void setDialect(JdbcDialectName dialect) {
                this.dialect = dialect;
            }

            /** Returns whether the schema is validated at startup. */
            public boolean isValidateSchema() {
                return validateSchema;
            }

            /** Sets whether the schema is validated at startup. */
            public void setValidateSchema(boolean validateSchema) {
                this.validateSchema = validateSchema;
            }

            /** Returns when the table is created. */
            public InitializeSchema getInitializeSchema() {
                return initializeSchema;
            }

            /** Sets when the table is created. */
            public void setInitializeSchema(InitializeSchema initializeSchema) {
                this.initializeSchema = initializeSchema;
            }
        }

        /** In-memory store settings. */
        public static class InMemory {

            /** Upper bound of records in the in-memory store. */
            private int maxEntries = InMemoryIdempotencyStore.DEFAULT_MAX_ENTRIES;

            /** Returns the upper bound. */
            public int getMaxEntries() {
                return maxEntries;
            }

            /** Sets the upper bound. */
            public void setMaxEntries(int maxEntries) {
                this.maxEntries = maxEntries;
            }
        }
    }

    /** Which store to use. */
    public enum StoreType {
        /** An application bean, then JDBC, then Redis, then in-memory. */
        AUTO,
        /** The JDBC store. */
        JDBC,
        /** The Redis store. */
        REDIS,
        /** The in-memory store. */
        IN_MEMORY
    }

    /** The database dialect of the JDBC store. */
    public enum JdbcDialectName {
        /** Detected from the connection. */
        AUTO,
        /** PostgreSQL. */
        POSTGRESQL,
        /** MySQL. */
        MYSQL,
        /** MariaDB. */
        MARIADB,
        /** Oracle. */
        ORACLE,
        /** SQL Server. */
        SQLSERVER
    }

    /** When the JDBC store creates its table. */
    public enum InitializeSchema {
        /** Never; the application's migrations create it. */
        NEVER,
        /** Only on an embedded database. */
        EMBEDDED,
        /** Whenever the table is missing. */
        ALWAYS
    }

    /** An idempotent operation declared in configuration. */
    public static class Route {

        /** HTTP methods of the operation. */
        private List<String> methods = new ArrayList<>(List.of("POST"));

        /** Path pattern of the operation, such as /orders/{id}. */
        private @Nullable String path;

        /** TTL of the operation; the default TTL when absent. */
        private @Nullable Duration ttl;

        /** Lease of the operation; the default lease when absent. */
        private @Nullable Duration lease;

        /** Whether a request without a key is rejected with 400. */
        private boolean required = true;

        /** Returns the methods. */
        public List<String> getMethods() {
            return methods;
        }

        /** Sets the methods. */
        public void setMethods(List<String> methods) {
            this.methods = methods;
        }

        /** Returns the path pattern. */
        public @Nullable String getPath() {
            return path;
        }

        /** Sets the path pattern. */
        public void setPath(@Nullable String path) {
            this.path = path;
        }

        /** Returns the TTL. */
        public @Nullable Duration getTtl() {
            return ttl;
        }

        /** Sets the TTL. */
        public void setTtl(@Nullable Duration ttl) {
            this.ttl = ttl;
        }

        /** Returns the lease. */
        public @Nullable Duration getLease() {
            return lease;
        }

        /** Sets the lease. */
        public void setLease(@Nullable Duration lease) {
            this.lease = lease;
        }

        /** Returns whether a key is required. */
        public boolean isRequired() {
            return required;
        }

        /** Sets whether a key is required. */
        public void setRequired(boolean required) {
            this.required = required;
        }
    }
}
