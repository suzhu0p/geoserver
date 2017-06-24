/* (c) 2017 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.opensearch.eo.store;

import static org.geoserver.opensearch.eo.store.JDBCOpenSearchAccess.FF;
import static org.geoserver.opensearch.eo.store.OpenSearchAccess.METADATA_PROPERTY_NAME;
import static org.geoserver.opensearch.eo.store.OpenSearchAccess.OGC_LINKS_PROPERTY_NAME;

import java.awt.RenderingHints.Key;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.geotools.data.DataAccess;
import org.geotools.data.DataSourceException;
import org.geotools.data.DataStore;
import org.geotools.data.DataUtilities;
import org.geotools.data.DefaultResourceInfo;
import org.geotools.data.FeatureListener;
import org.geotools.data.FeatureReader;
import org.geotools.data.FeatureStore;
import org.geotools.data.Join;
import org.geotools.data.Join.Type;
import org.geotools.data.Query;
import org.geotools.data.QueryCapabilities;
import org.geotools.data.ResourceInfo;
import org.geotools.data.Transaction;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.data.store.EmptyFeatureCollection;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.Hints;
import org.geotools.feature.AttributeBuilder;
import org.geotools.feature.ComplexFeatureBuilder;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;
import org.opengis.feature.Attribute;
import org.opengis.feature.Feature;
import org.opengis.feature.FeatureFactory;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.Name;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.filter.Filter;
import org.opengis.filter.Id;
import org.opengis.filter.expression.PropertyName;
import org.opengis.filter.identity.FeatureId;
import org.opengis.filter.sort.SortBy;
import org.opengis.filter.sort.SortOrder;

/**
 * Base class for the collection and product specific feature source wrappers
 *
 * @author Andrea Aime - GeoSolutions
 */
public abstract class AbstractMappingStore implements FeatureStore<FeatureType, Feature> {

    static final FeatureFactory FEATURE_FACTORY = CommonFactoryFinder.getFeatureFactory(null);

    static final Logger LOGGER = Logging.getLogger(AbstractMappingStore.class);

    protected JDBCOpenSearchAccess openSearchAccess;

    protected FeatureType schema;

    protected SourcePropertyMapper propertyMapper;

    protected SortBy[] defaultSort;

    private SimpleFeatureType linkFeatureType;

    private Transaction transaction;

    public AbstractMappingStore(JDBCOpenSearchAccess openSearchAccess,
            FeatureType collectionFeatureType) throws IOException {
        this.openSearchAccess = openSearchAccess;
        this.schema = collectionFeatureType;
        this.propertyMapper = new SourcePropertyMapper(schema);
        this.defaultSort = buildDefaultSort(schema);
        this.linkFeatureType = buildLinkFeatureType();
    }

    protected SimpleFeatureType buildLinkFeatureType() throws IOException {
        SimpleFeatureType source = openSearchAccess.getDelegateStore().getSchema(getLinkTable());
        try {
            SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
            b.init(source);
            b.setName(openSearchAccess.OGC_LINKS_PROPERTY_NAME);
            return b.buildFeatureType();
        } catch (Exception e) {
            throw new DataSourceException("Could not build the renamed feature type.", e);
        }
    }

    /**
     * Builds the default sort for the underlying feature source query
     * 
     * @param schema
     * @return
     */
    protected SortBy[] buildDefaultSort(FeatureType schema) {
        String timeStart = propertyMapper.getSourceName("timeStart");
        String identifier = propertyMapper.getSourceName("identifier");
        return new SortBy[] { FF.sort(timeStart, SortOrder.DESCENDING),
                FF.sort(identifier, SortOrder.ASCENDING) };
    }

    @Override
    public Name getName() {
        return schema.getName();
    }

    @Override
    public ResourceInfo getInfo() {
        try {
            SimpleFeatureSource featureSource = getDelegateCollectionSource();
            ResourceInfo delegateInfo = featureSource.getInfo();
            DefaultResourceInfo result = new DefaultResourceInfo(delegateInfo);
            result.setSchema(new URI(schema.getName().getNamespaceURI()));
            return result;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /*
     * Returns the underlying delegate source
     */
    protected abstract SimpleFeatureSource getDelegateCollectionSource() throws IOException;

    protected SimpleFeatureStore getDelegateCollectionStore() throws IOException {
        SimpleFeatureStore fs = (SimpleFeatureStore) getDelegateCollectionSource();
        return fs;
    }

    @Override
    public DataAccess<FeatureType, Feature> getDataStore() {
        return openSearchAccess;
    }

    @Override
    public QueryCapabilities getQueryCapabilities() {
        QueryCapabilities result = new QueryCapabilities() {
            @Override
            public boolean isOffsetSupported() {
                return true;
            }

            @Override
            public boolean isReliableFIDSupported() {
                // the delegate store should have a primary key on collections
                return true;
            }
        };
        return result;
    }

    @Override
    public void addFeatureListener(FeatureListener listener) {
        throw new UnsupportedOperationException();

    }

    @Override
    public void removeFeatureListener(FeatureListener listener) {
        throw new UnsupportedOperationException();

    }

    @Override
    public FeatureCollection<FeatureType, Feature> getFeatures(Filter filter) throws IOException {
        return getFeatures(new Query(getSchema().getName().getLocalPart(), filter));
    }

    @Override
    public FeatureCollection<FeatureType, Feature> getFeatures() throws IOException {
        return getFeatures(Query.ALL);
    }

    @Override
    public FeatureType getSchema() {
        return schema;
    }

    @Override
    public ReferencedEnvelope getBounds() throws IOException {
        return getDelegateCollectionSource().getBounds();
    }

    @Override
    public ReferencedEnvelope getBounds(Query query) throws IOException {
        Query mapped = mapToSimpleCollectionQuery(query, false);
        return getDelegateCollectionSource().getBounds(mapped);
    }

    @Override
    public Set<Key> getSupportedHints() {
        try {
            return getDelegateCollectionSource().getSupportedHints();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public int getCount(Query query) throws IOException {
        final Query mappedQuery = mapToSimpleCollectionQuery(query, false);
        return getDelegateCollectionSource().getCount(mappedQuery);
    }

    /**
     * Maps query back the main underlying feature source
     * 
     * @param query
     * @return
     * @throws IOException
     */
    protected Query mapToSimpleCollectionQuery(Query query, boolean addJoins) throws IOException {
        Query result = new Query(getDelegateCollectionSource().getSchema().getTypeName());
        final Filter originalFilter = query.getFilter();
        if (originalFilter != null) {
            Filter mappedFilter = mapFilterToDelegateSchema(originalFilter);
            result.setFilter(mappedFilter);
        }
        if (query.getPropertyNames() != null && query.getPropertyNames().length > 0) {
            String[] mappedPropertyNames = Arrays.stream(query.getPropertyNames())
                    .map(name -> propertyMapper.getSourceName(name)).filter(name -> name != null)
                    .toArray(size -> new String[size]);
            if (mappedPropertyNames.length == 0) {
                result.setPropertyNames(Query.ALL_NAMES);
            } else {
                result.setPropertyNames(mappedPropertyNames);
            }
        }
        if (query.getSortBy() != null && query.getSortBy().length > 0) {
            SortBy[] mappedSortBy = Arrays.stream(query.getSortBy()).map(sb -> {
                if (sb == SortBy.NATURAL_ORDER || sb == SortBy.REVERSE_ORDER) {
                    return sb;
                } else {
                    String name = sb.getPropertyName().getPropertyName();
                    String mappedName = propertyMapper.getSourceName(name);
                    if (mappedName == null) {
                        throw new IllegalArgumentException("Cannot sort on " + name);
                    }
                    return FF.sort(mappedName, sb.getSortOrder());
                }
            }).toArray(size -> new SortBy[size]);
            result.setSortBy(mappedSortBy);
        } else {
            // get stable results for paging
            result.setSortBy(defaultSort);
        }

        if (addJoins) {
            // join to metadata table if necessary
            if (hasOutputProperty(query, METADATA_PROPERTY_NAME, false)) {
                Filter filter = FF.equal(FF.property("id"), FF.property("metadata.mid"), true);
                final String metadataTable = getMetadataTable();
                Join join = new Join(metadataTable, filter);
                join.setAlias("metadata");
                join.setType(Type.OUTER);
                result.getJoins().add(join);
            }

            // same goes for OGC links (they might be missing, so outer join is used)
            if (hasOutputProperty(query, OGC_LINKS_PROPERTY_NAME, true)) {
                final String linkTable = getLinkTable();
                final String linkForeignKey = getLinkForeignKey();
                Filter filter = FF.equal(FF.property("id"), FF.property("link." + linkForeignKey),
                        true);
                Join join = new Join(linkTable, filter);
                join.setAlias("link");
                join.setType(Type.OUTER);
                result.getJoins().add(join);
            }
        } else {
            // only non joined requests are pageable
            result.setStartIndex(query.getStartIndex());
            result.setMaxFeatures(query.getMaxFeatures());
        }

        return result;
    }

    private Filter mapFilterToDelegateSchema(final Filter filter) {
        MappingFilterVisitor visitor = new MappingFilterVisitor(propertyMapper);
        Filter mappedFilter = (Filter) filter.accept(visitor, null);
        return mappedFilter;
    }

    /**
     * Name of the metadata table to join in case the {@link OpenSearchAccess#METADATA_PROPERTY_NAME} property is requested
     * 
     * @return
     */
    protected abstract String getMetadataTable();

    /**
     * Name of the link table to join in case the {@link OpenSearchAccess#OGC_LINKS_PROPERTY_NAME} property is requested
     * 
     * @return
     */
    protected abstract String getLinkTable();

    /**
     * Name of the field linking back to the main table in case the {@link OpenSearchAccess#OGC_LINKS_PROPERTY_NAME} property is requested
     * 
     * @return
     */
    protected abstract String getLinkForeignKey();

    /**
     * Searches for an optional property among the query attributes. Returns true only if the property is explicitly listed
     * 
     * @param query
     * @param property
     * @return
     */
    protected boolean hasOutputProperty(Query query, Name property, boolean includedByDefault) {
        if (query.getProperties() == null) {
            return includedByDefault;
        }

        final String localPart = property.getLocalPart();
        final String namespaceURI = property.getNamespaceURI();
        for (PropertyName pn : query.getProperties()) {
            if (localPart.equals(pn.getPropertyName())
                    && namespaceURI.equals(pn.getNamespaceContext().getURI(""))) {
                return true;
            }
        }

        return false;
    }

    @Override
    public FeatureCollection<FeatureType, Feature> getFeatures(Query query) throws IOException {
        // first get the ids of the features we are going to return, no joins to support paging
        Query idsQuery = mapToSimpleCollectionQuery(query, false);
        // idsQuery.setProperties(Query.NO_PROPERTIES); (no can do, there are mandatory fields)
        SimpleFeatureCollection idFeatureCollection = getDelegateCollectionSource()
                .getFeatures(idsQuery);

        Set<FeatureId> ids = new LinkedHashSet<>();
        idFeatureCollection.accepts(f -> ids.add(f.getIdentifier()), null);

        // if no features, return immediately
        SimpleFeatureCollection fc;
        if (ids.isEmpty()) {
            fc = new EmptyFeatureCollection(getDelegateCollectionSource().getSchema());
        } else {
            // the run a joined query with the specified ids
            Query dataQuery = mapToSimpleCollectionQuery(query, true);
            dataQuery.setFilter(FF.id(ids));
            fc = getDelegateCollectionSource().getFeatures(dataQuery);
        }

        return new MappingFeatureCollection(schema, fc, this::mapToComplexFeature);

    }

    /**
     * Maps the underlying features (eventually joined) to the output complex feature
     * 
     * @param it
     * @return
     */
    protected Feature mapToComplexFeature(PushbackFeatureIterator<SimpleFeature> it) {
        SimpleFeature fi = it.next();

        ComplexFeatureBuilder builder = new ComplexFeatureBuilder(schema);

        // allow subclasses to perform custom mappings while reusing the common ones
        mapPropertiesToComplex(builder, fi);

        // the OGC links can be more than one
        Object link = fi.getAttribute("link");
        while (link instanceof SimpleFeature) {
            // retype the feature to have the right name
            SimpleFeature linkFeature = SimpleFeatureBuilder.retype((SimpleFeature) link,
                    linkFeatureType);
            builder.append(OGC_LINKS_PROPERTY_NAME, linkFeature);

            // see if there are more links
            if (it.hasNext()) {
                SimpleFeature next = it.next();
                // same feature?
                if (next.getID().equals(fi.getID())) {
                    link = next.getAttribute("link");
                } else {
                    // moved to the next feature, push it back,
                    // we're done for the current one
                    it.pushBack();
                    break;
                }
            } else {
                break;
            }

        }

        //
        Feature feature = builder.buildFeature(fi.getID());
        return feature;
    }

    /**
     * Performs the common mappings, subclasses can override to add more
     * 
     * @param builder
     * @param fi
     */
    protected void mapPropertiesToComplex(ComplexFeatureBuilder builder, SimpleFeature fi) {
        AttributeBuilder ab = new AttributeBuilder(FEATURE_FACTORY);
        for (PropertyDescriptor pd : schema.getDescriptors()) {
            if (!(pd instanceof AttributeDescriptor)) {
                continue;
            }
            String localName = (String) pd.getUserData().get(JDBCOpenSearchAccess.SOURCE_ATTRIBUTE);
            if (localName == null) {
                continue;
            }
            Object value = fi.getAttribute(localName);
            if (value == null) {
                continue;
            }
            ab.setDescriptor((AttributeDescriptor) pd);
            Attribute attribute = ab.buildSimple(null, value);
            builder.append(pd.getName(), attribute);
        }

        // handle joined metadata
        Object metadataValue = fi.getAttribute("metadata");
        if (metadataValue instanceof SimpleFeature) {
            SimpleFeature metadataFeature = (SimpleFeature) metadataValue;
            ab.setDescriptor((AttributeDescriptor) schema.getDescriptor(METADATA_PROPERTY_NAME));
            Attribute attribute = ab.buildSimple(null, metadataFeature.getAttribute("metadata"));
            builder.append(METADATA_PROPERTY_NAME, attribute);
        }

    }

    /**
     * Maps a complex feature back to one or more simple features
     * 
     * @param it
     * @return
     * @throws IOException
     */
    protected SimpleFeature mapToMainSimpleFeature(Feature feature) throws IOException {
        // map the primary simple feature
        final SimpleFeatureType simpleSchema = getDelegateCollectionSource().getSchema();
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(simpleSchema);
        for (PropertyDescriptor pd : schema.getDescriptors()) {
            if (!(pd instanceof AttributeDescriptor)) {
                continue;
            }
            String localName = (String) pd.getUserData().get(JDBCOpenSearchAccess.SOURCE_ATTRIBUTE);
            if (localName == null) {
                continue;
            }
            Property property = feature.getProperty(pd.getName());
            if (property == null || property.getValue() == null) {
                continue;
            }
            fb.set(localName, property.getValue());
        }
        SimpleFeature sf = fb.buildFeature(null);

        return sf;
    }

    protected List<SimpleFeature> mapToSecondarySimpleFeatures(Feature feature) {
        // TODO: handle OGC links, but for the moment the code uses modifyFeatures to add those
        return Collections.emptyList();
    }

    @Override
    public List<FeatureId> addFeatures(FeatureCollection<FeatureType, Feature> featureCollection)
            throws IOException {
        // silly implementation assuming there will be only one insert at a time (which is
        // indeed the case for the current REST API), needs to be turned into a streaming
        // approach in case we want to handle larger data volumes
        final DataStore delegateStore = openSearchAccess.getDelegateStore();
        List<FeatureId> result = new ArrayList<>();
        try (FeatureIterator it = featureCollection.features()) {
            Feature feature = it.next();
            SimpleFeature simpleFeature = mapToMainSimpleFeature(feature);
            SimpleFeatureStore store = getDelegateCollectionStore();
            store.setTransaction(getTransaction());
            List<FeatureId> ids = store.addFeatures(DataUtilities.collection(simpleFeature));
            result.addAll(ids);

            List<SimpleFeature> simpleFeatures = mapToSecondarySimpleFeatures(feature);
            for (SimpleFeature sf : simpleFeatures) {
                SimpleFeatureStore fs = (SimpleFeatureStore) delegateStore
                        .getFeatureSource(sf.getType().getTypeName());
                if (fs == null) {
                    throw new IOException(
                            "Could not find a delegate feature store for unmapped feature " + sf);
                }
                fs.setTransaction(getTransaction());
                fs.addFeatures(DataUtilities.collection(sf));
            }
        }

        return result;
    }

    @Override
    public void removeFeatures(Filter filter) throws IOException {
        Filter mappedFilter = mapFilterToDelegateSchema(filter);

        // remove all related metadata
        final List<String> collectionIdentifiers = getCollectionDatabaseIdentifiers(mappedFilter);
        List<Filter> filters = collectionIdentifiers.stream()
                .map(id -> FF.equal(FF.property("mid"), FF.literal(id), false))
                .collect(Collectors.toList());
        Filter metadataFilter = FF.or(filters);
        SimpleFeatureStore metadataStore = getFeatureStoreForTable(getMetadataTable());
        metadataStore.setTransaction(getTransaction());
        metadataStore.removeFeatures(metadataFilter);

        // remove all related OGC links
        filters = collectionIdentifiers.stream()
                .map(id -> FF.equal(FF.property("collection_id"), FF.literal(id), false))
                .collect(Collectors.toList());
        Filter linksFilter = FF.or(filters);
        metadataStore = getFeatureStoreForTable(getLinkTable());
        metadataStore.setTransaction(getTransaction());
        metadataStore.removeFeatures(linksFilter);

        // finally drop the collections themselves
        SimpleFeatureStore store = getDelegateCollectionStore();
        store.removeFeatures(mappedFilter);
    }

    @Override
    public void modifyFeatures(AttributeDescriptor[] types, Object[] values, Filter filter)
            throws IOException {
        Name[] names = Stream.of(types).map(type -> type.getName()).toArray(size -> new Name[size]);
        modifyFeatures(names, values, filter);
    }

    @Override
    public void modifyFeatures(Name attributeName, Object attributeValue, Filter filter)
            throws IOException {
        modifyFeatures(new Name[] { attributeName }, new Object[] { attributeValue }, filter);
    }

    @Override
    public void modifyFeatures(AttributeDescriptor type, Object value, Filter filter)
            throws IOException {
        modifyFeatures(type.getName(), value, filter);
    }

    @Override
    public void modifyFeatures(Name[] attributeNames, Object[] attributeValues, Filter filter)
            throws IOException {
        Filter mappedFilter = mapFilterToDelegateSchema(filter);

        // map names to local simple feature, store out the delegate ones
        List<String> localNames = new ArrayList<>();
        List<Object> localValues = new ArrayList<>();
        for (int i = 0; i < attributeNames.length; i++) {
            Name name = attributeNames[i];
            Object value = attributeValues[i];
            // sub-table related updates
            if (OpenSearchAccess.METADATA_PROPERTY_NAME.equals(name)) {
                final String tableName = getMetadataTable();
                SimpleFeatureStore metadataStore = getFeatureStoreForTable(tableName);
                metadataStore.setTransaction(getTransaction());
                for (String id : getCollectionDatabaseIdentifiers(mappedFilter)) {
                    Id secondaryTableFilter = FF.id(FF.featureId(tableName + "." + id));
                    // make it a delete and eventually insert case, easier to code and no more queries
                    // than checking if the metadata was already there to perform an update
                    metadataStore.removeFeatures(secondaryTableFilter);
                    if (value != null) {
                        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(
                                metadataStore.getSchema());
                        fb.set("mid", id);
                        fb.set("metadata", value);
                        SimpleFeature metadataFeature = fb.buildFeature(tableName + "." + id);
                        metadataFeature.getUserData().put(Hints.USE_PROVIDED_FID, true);
                        metadataStore.addFeatures(DataUtilities.collection(metadataFeature));
                    }
                }

                // this one has been handled
                continue;
            }
            if (OpenSearchAccess.QUICKLOOK_PROPERTY_NAME.equals(name)) {
                throw new UnsupportedOperationException("Still cannot update the quicklook");
            }
            if (OpenSearchAccess.OGC_LINKS_PROPERTY_NAME.equals(name)) {
                final String table = getLinkTable();
                SimpleFeatureStore linksStore = getFeatureStoreForTable(table);
                linksStore.setTransaction(getTransaction());
                for (String id : getCollectionDatabaseIdentifiers(mappedFilter)) {
                    Filter linkTableFilter = FF.equal(FF.property("collection_id"), FF.literal(id), true);
                    // make it a delete and eventually insert case, easier to code and no more queries
                    // than checking if the metadata was already there to perform an update
                    linksStore.removeFeatures(linkTableFilter);
                    if (value != null) {
                        SimpleFeatureCollection links = (SimpleFeatureCollection) value;
                        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(linksStore.getSchema());
                        ListFeatureCollection mappedLinks = new ListFeatureCollection(linksStore.getSchema());
                        links.accepts(f -> {
                            SimpleFeature sf = (SimpleFeature) f;
                            for (AttributeDescriptor ad : linksStore.getSchema().getAttributeDescriptors()) {
                                fb.set(ad.getLocalName(), sf.getAttribute(ad.getLocalName()));
                            }
                            fb.set("collection_id", id);
                            SimpleFeature mappedLink = fb.buildFeature(null);
                            mappedLinks.add(mappedLink);
                        }, null);
                        linksStore.addFeatures(mappedLinks);
                    }
                }

                // this one has been handled
                continue;
            }

            PropertyDescriptor descriptor = schema.getDescriptor(name);
            if (!(descriptor instanceof AttributeDescriptor)) {
                throw new IllegalArgumentException(
                        "Did not expect modification on attribute " + name);
            }
            String localName = (String) descriptor.getUserData()
                    .get(JDBCOpenSearchAccess.SOURCE_ATTRIBUTE);
            if (localName == null) {
                throw new IllegalArgumentException(
                        "Did not expect modification on attribute " + name);
            }
            localNames.add(localName);
            localValues.add(value);
        }

        // update primary table
        if (localNames.size() > 0) {
            String[] nameArray = (String[]) localNames.toArray(new String[localNames.size()]);
            Object[] valueArray = (Object[]) localValues.toArray(new Object[localValues.size()]);
            getDelegateCollectionStore().modifyFeatures(nameArray, valueArray, mappedFilter);
        }
    }

    protected SimpleFeatureStore getFeatureStoreForTable(final String table) throws IOException {
        SimpleFeatureStore featureStore = (SimpleFeatureStore) openSearchAccess.getDelegateStore()
                .getFeatureSource(table);
        return featureStore;
    }

    public List<String> getCollectionDatabaseIdentifiers(Filter filter) throws IOException {
        SimpleFeatureCollection idFeatureCollection = getDelegateCollectionSource()
                .getFeatures(filter);
        List<String> result = new ArrayList<>();
        try (SimpleFeatureIterator fi = idFeatureCollection.features()) {
            while (fi.hasNext()) {
                SimpleFeature f = fi.next();
                String progressive = f.getIdentifier().toString().split("\\.")[1];
                result.add(progressive);
            }
        }
        return result;
    }

    @Override
    public void setFeatures(FeatureReader<FeatureType, Feature> reader) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }

    @Override
    public Transaction getTransaction() {
        if (transaction == null) {
            return Transaction.AUTO_COMMIT;
        } else {
            return this.transaction;
        }
    }

}
