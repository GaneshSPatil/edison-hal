package de.otto.edison.hal;

import com.damnhandy.uri.template.UriTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.damnhandy.uri.template.UriTemplate.fromTemplate;
import static de.otto.edison.hal.HalParser.EmbeddedTypeInfo;
import static de.otto.edison.hal.HalParser.EmbeddedTypeInfo.withEmbedded;
import static de.otto.edison.hal.HalParser.parse;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;

/**
 * A Traverson is a utility that makes it easy to navigate REST APIs using HAL+JSON.
 * <p>
 *     {@link #startWith(String) Starting with} an URI to an initial resource, you can {@link #follow(String)} one or more
 *     links identified by their link-relation type. {@link Link#templated(String, String) Templated links} can be
 *     expanded using template {@link #withVars(String, Object, Object...) variables}.
 * </p>
 * <p>
 *     Example:
 * </p>
 * <pre><code>
 *        final HalRepresentation hal = traverson(this::myHttpGetFunction)
 *                .startWith("http://example.org")
 *                .follow("foobar")
 *                .follow(
 *                        hops("foo", "bar"),
 *                        withVars("param1", "value1", "param2", "value2"))
 *                .get();
 * </code></pre>
 * <p>
 *     Embedded resources are used instead of linked resources, if present.
 * </p>
 * @since 0.4.0
 */
public class Traverson {

    private static class Hop {
        /** Link-relation type of the hop. */
        final String rel;
        /** URI-template variables used when following the hop. */
        final Map<String,Object> vars;

        private Hop(final String rel,
                    final Map<String, Object> vars) {
            this.rel = rel;
            this.vars = vars;
        }
    }

    private final Function<String, String> uriToJsonFunc;
    private final List<Hop> hops = new ArrayList<>();
    private String startWith;
    private List<? extends HalRepresentation> lastResult;

    private Traverson(final Function<String,String> uriToJsonFunc) {
        this.uriToJsonFunc = uriToJsonFunc;
    }

    /**
     * <p>
     *     Create a Traverson that is using the given {@link Function} to resolve an URI and return a HAL+JSON document.
     * </p>
     * <p>
     *     Typical implementations of the Function will rely on some HTTP client.
     * </p>
     * @param getJsonFromUri A Function that gets an URI of a resource and returns a HAL+JSON document.
     * @return Traverson
     */
    public static Traverson traverson(final Function<String,String> getJsonFromUri) {
        return new Traverson(getJsonFromUri);
    }

    /**
     * Creates a Map containing key-value pairs used as parameters for UriTemplate variables.
     * <p>
     *     Basically, this method is only adding some semantic sugar, so you can write code like this:
     * </p>
     * <pre><code>
     *     traverson(this::httpGet)
     *             .startWith("http://example.org")
     *             .follow("foo", withVars("param1", "value1", "param2", "value2"))
     *             .get();
     * </code></pre>
     *
     * @param key the first key
     * @param value the value associated with the first key
     * @param more Optionally more key-value pairs. The number of parameters is must even, keys must be Strings.
     * @return Map
     */
    public static Map<String, Object> withVars(final String key, final Object value, final Object... more) {
        if (more != null && more.length % 2 != 0) {
            throw new IllegalArgumentException("The number of template variables (key-value pairs) must be even");
        }
        final Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        if (more != null) {
            for (int i=0; i<more.length/2; i+=2) {
                map.put(more[i].toString(), more[i+1]);
            }
        }
        return map;
    }

    /**
     * Creates a list of link-relation types used to {@link #follow(List) follow} multiple hops, optionally
     * using {@link #follow(List, Map) uri template variables}.
     * <p>
     *     This method is only adding some semantic sugar, so you can write code like this:
     * </p>
     * <pre><code>
     *     traverson(this::httpGet)
     *             .startWith("http://example.org")
     *             .follow(
     *                     <strong>hops("foo", "bar"),</strong>
     *                     withVars("param1", "value1", "param2", "value2"))
     *             .get();
     * </code></pre>
     *
     * @param rel link-relation type
     * @param moreRels optionally more link-relation types
     * @return list of link-relation types
     */
    public static List<String> hops(final String rel, final String... moreRels) {
        if (moreRels != null) {
            return new ArrayList<String>() {{
                add(rel);
                addAll(asList(moreRels));
            }};
        } else {
            return singletonList(rel);
        }
    }

    /**
     * Start traversal at the application/hal+json resource idenfied by {@code uri}.
     *
     * @param uriTemplate the {@code URI} of the initial HAL resource.
     * @param vars uri-template variables used to build links.
     * @return Traverson initialized with the {@link HalRepresentation} identified by {@code uri}.
     */
    public Traverson startWith(final String uriTemplate, final Map<String, Object> vars) {
        return startWith(fromTemplate(uriTemplate).expand(vars));
    }

    /**
     * Start traversal at the application/hal+json resource idenfied by {@code uri}.
     *
     * @param uri the {@code URI} of the initial HAL resource.
     * @return Traverson initialized with the {@link HalRepresentation} identified by {@code uri}.
     */
    public Traverson startWith(final String uri) {
        startWith = uri;
        lastResult = null;
        return this;
    }

    /**
     * Follow the first {@link Link} of the current resource, selected by it's link-relation type.
     * <p>
     *     If the current node has {@link Embedded embedded} items with the specified {@code rel},
     *     these items are used instead of following the associated {@link Link}.
     * </p>
     * @param rel the link-relation type of the followed link
     * @return this
     */
    public Traverson follow(final String rel) {
        return follow(rel, emptyMap());
    }

    /**
     * Follow multiple link-relation types, one by one.
     * <p>
     *     Embedded items are used instead of resolving links, if present in the returned HAL documents.
     * </p>
     *
     * @param rels the link-relation types of the followed links
     * @return this
     */
    public Traverson follow(final List<String> rels) {
        return follow(rels, emptyMap());
    }

    /**
     * Follow multiple link-relation types, one by one.
     * <p>
     *     Templated links are resolved to URIs using the specified template variables.
     * </p>
     * <p>
     *     Embedded items are used instead of resolving links, if present in the returned HAL documents.
     * </p>
     *
     * @param rels the link-relation types of the followed links
     * @param vars uri-template variables used to build links.
     * @return this
     */
    public Traverson follow(final List<String> rels, final Map<String, Object> vars) {
        for (String rel : rels) {
            follow(rel, vars);
        }
        return this;
    }

    /**
     * Follow the first {@link Link} of the current resource, selected by it's link-relation type.
     * <p>
     *     Templated links are resolved to URIs using the specified template variables.
     * </p>
     * <p>
     *     If the current node has {@link Embedded embedded} items with the specified {@code rel},
     *     these items are used instead of following the associated {@link Link}.
     * </p>
     * @param rel the link-relation type of the followed link
     * @param vars uri-template variables used to build links.
     * @return this
     */
    public Traverson follow(final String rel, final Map<String, Object> vars) {
        checkState();
        hops.add(new Hop(rel, vars));
        return this;
    }

    /**
     * Follow the {@link Link}s of the current resource, selected by it's link-relation type and returns a {@link Stream}
     * containing the returned {@link HalRepresentation HalRepresentations}.
     * <p>
     *     If the current node has {@link Embedded embedded} items with the specified {@code rel},
     *     these items are used instead of following the associated {@link Link}.
     * </p>
     * @return this
     */
    public Stream<HalRepresentation> stream() {
        return streamAs(HalRepresentation.class);
    }

    /**
     * Follow the {@link Link}s of the current resource, selected by it's link-relation type and returns a {@link Stream}
     * containing the returned {@link HalRepresentation HalRepresentations}.
     * <p>
     *     Templated links are resolved to URIs using the specified template variables.
     * </p>
     * <p>
     *     If the current node has {@link Embedded embedded} items with the specified {@code rel},
     *     these items are used instead of following the associated {@link Link}.
     * </p>
     * @param type the type of the returned HalRepresentations
     * @param <T> type of the returned HalRepresentations
     * @return this
     */
    @SuppressWarnings("unchecked")
    public <T extends HalRepresentation> Stream<T> streamAs(final Class<T> type) {
        checkState();
        if (startWith != null) {
            lastResult = traverse(type, true);
        } else if (!hops.isEmpty()) {
            lastResult = traverse(lastResult.get(0), type, true);
        }
        return (Stream<T>) lastResult.stream();
    }

    /**
     * Return the selected HalRepresentation.
     * <p>
     *     If there are multiple matching representations, the first node is returned.
     * </p>
     *
     * @return HalRepresentation
     */
    public HalRepresentation get() {
        return getAs(HalRepresentation.class);
    }

    /**
     * Return the selected HalRepresentation and return it in the specified type.
     *
     * @param type the subtype of the HalRepresentation used to parse the resource.
     * @param <T> the subtype of HalRepresentation
     * @return HalRepresentation
     */
    public <T extends HalRepresentation> T getAs(final Class<T> type) {
        checkState();
        if (startWith != null) {
            lastResult = traverse(type, false);
        } else if (!hops.isEmpty()) {
            lastResult = traverse(lastResult.get(0), type, false);
        }
        return (T) lastResult.get(0);
    }

    private <T extends HalRepresentation> List<T> traverse(final Class<T> type, final boolean retrieveAll) {
        final String href = this.startWith;
        this.startWith = null;
        if (hops.isEmpty()) {
            return singletonList(getResource(href, type, null));
        } else {
            final HalRepresentation firstHop;
            if (hops.size() == 1) {
                final Hop hop = hops.get(0);
                firstHop = getResource(href, HalRepresentation.class, withEmbedded(hop.rel, type));
            } else {
                firstHop = getResource(href, HalRepresentation.class, null);
            }
            return traverse(firstHop, type, retrieveAll);
        }
    }

    @SuppressWarnings("unchecked")
    private <T extends HalRepresentation> List<T> traverse(final HalRepresentation current, final Class<T> resultType, boolean retrieveAll) {

        final Hop currentHop = hops.remove(0);

        // the next hop could possibly be already available as an embedded object:
        final List<? extends HalRepresentation> items = hops.isEmpty()
                ? current.getEmbedded().getItemsBy(currentHop.rel, resultType)
                : current.getEmbedded().getItemsBy(currentHop.rel);
        if (!items.isEmpty()) {

            return hops.isEmpty()
                    ? (List<T>) items
                    : traverse(items.get(0), resultType, retrieveAll);
        }

        final List<Link> links = current
                .getLinks()
                .getLinksBy(currentHop.rel);
        if (links.isEmpty()) {
            throw new IllegalStateException("Can not follow hop " + currentHop.rel + ": no matching links found in resource " + current);
        }
        final String href = toHref(links.get(0), currentHop.vars);

        if (hops.isEmpty()) { // last hop
            if (retrieveAll) {
                return links
                        .stream()
                        .map(link-> getResource(toHref(link, currentHop.vars), resultType, null))
                        .collect(toList());
            } else {
                return singletonList(getResource(href, resultType, null));
            }
        }

        if (hops.size() == 1) { // one before the last hop:
            final Hop nextHop = hops.get(0);
            return traverse(getResource(href, HalRepresentation.class, withEmbedded(nextHop.rel, resultType)), resultType, retrieveAll);
        } else { // some more hops
            return traverse(getResource(href, HalRepresentation.class, null), resultType, retrieveAll);
        }
    }

    private String toHref(final Link link, final Map<String,Object> vars) {
        return link.isTemplated()
                ? UriTemplate.fromTemplate(link.getHref()).expand(vars)
                : link.getHref();
    }

    /**
     * Retrieve the HAL resource identified by {@code uri} and return the representation as a HopResponse.
     *
     * @param uri the URI of the resource to retrieve
     * @return HopResponse
     */
    private <T extends HalRepresentation> T getResource(final String uri, final Class<T> type, final EmbeddedTypeInfo<?> embeddedType) {
        try {
            final String json = uriToJsonFunc.apply(uri);
            return embeddedType != null
                    ? parse(json).as(type, embeddedType)
                    : parse(json).as(type);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Checks the current state of the Traverson.
     *
     * @throws IllegalStateException if some error occured during traversion
     */
    private void checkState() {
        if (startWith == null && lastResult == null) {
            throw new IllegalStateException("Please call startWith(uri) first.");
        }
    }
}
