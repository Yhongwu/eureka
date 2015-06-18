package com.netflix.eureka2.registry;

import java.util.UUID;

/**
 * A source class that must contain the origin of the source, and optionally a name. An unique id will be generated
 * for each source regardless of it's origin or name.
 *
 * @author David Liu
 */
public class Source {
    /**
     * Each entry in a registry is associated with exactly one origin:
     * <ul>
     * <li>{@link #LOCAL}</li> - there is an opened registration client connection to the write local server
     * <li>{@link #REPLICATED}</li> - replicated entry from another server
     * <li>{@link #BOOTSTRAP}</li> - entry loaded from external, bootstrap resource
     * <li>{@link #INTERESTED}</li> - entry from a source server specified as an interest
     * </ul>
     */
    public enum Origin { LOCAL, REPLICATED, BOOTSTRAP, INTERESTED }

    private final Origin origin;
    private final String name;  // nullable
    private final long id;

    private Source() {  // for Json and Avro
        this(null);
    }

    public Source(Origin origin) {
        this(origin, null);
    }

    public Source(Origin origin, String name) {
        this(origin, name, UUID.randomUUID().getLeastSignificantBits());
    }

    public Source(Origin origin, String name, long id) {
        this.origin = origin;
        this.name = name;
        this.id = id;
    }

    public Origin getOrigin() {
        return origin;
    }

    public String getName() {
        return name;
    }

    public long getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Source)) return false;

        Source source = (Source) o;

        if (id != source.id) return false;
        if (name != null ? !name.equals(source.name) : source.name != null) return false;
        if (origin != source.origin) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = origin != null ? origin.hashCode() : 0;
        result = 31 * result + (name != null ? name.hashCode() : 0);
        result = 31 * result + (int) (id ^ (id >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "Source{" +
                "origin=" + origin +
                ", name='" + name + '\'' +
                ", id='" + id + '\'' +
                '}';
    }

    public static SourceMatcher matcherFor(final Source source) {
        return new SourceMatcher() {
            @Override
            public boolean match(Source another) {
                return (source == null)
                        ? (another == null)
                        : source.equals(another);
            }
            @Override
            public String toString() {
                return "Matcher{" + source.toString() + "}";
            }
        };
    }

    public static SourceMatcher matcherFor(final Origin origin) {
        return new SourceMatcher() {
            @Override
            public boolean match(Source another) {
                if (another == null) {
                    return false;
                }
                return origin.equals(another.origin);
            }
        };
    }

    public static SourceMatcher matcherFor(final Origin origin, final String name) {
        return new SourceMatcher() {
            @Override
            public boolean match(Source another) {
                if (another == null) {
                    return false;
                }
                boolean originMatches = origin.equals(another.origin);
                boolean nameMatches = (name == null)
                        ? (another.name == null)
                        : name.equals(another.name);
                return originMatches && nameMatches;
            }
        };
    }

    public static abstract class SourceMatcher {
        public abstract boolean match(Source another);
    }
}
