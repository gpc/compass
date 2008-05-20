/*
 * Copyright 2004-2006 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.compass.core.lucene.engine.all;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.Payload;
import org.compass.core.Property;
import org.compass.core.engine.SearchEngineException;
import org.compass.core.lucene.engine.LuceneSearchEngine;
import org.compass.core.mapping.AllMapping;
import org.compass.core.mapping.ResourceMapping;
import org.compass.core.mapping.ResourcePropertyMapping;
import org.compass.core.spi.InternalProperty;
import org.compass.core.spi.InternalResource;

/**
 * The All Analyzer is a specific analyzer that is used to wrap the analyzer passed when adding
 * a document. It will gather all the tokens that the actual analyzer generates for fields that
 * are included in All and allow to get them using {@link #createAllTokenStream()} (which will
 * be used to create the all field with).
 *
 * <p>Un tokenized fields (which will not go through the analysis process) are identied when this
 * analyzed is constructed and are added to the all field if they are supposed to be included.
 * There are two options with the untokenized fields, either add them as is (un tokenized), or
 * analyze them just for the all properties.
 *
 * @author kimchy
 */
public class AllAnalyzer extends Analyzer {

    private Analyzer analyzer;

    private InternalResource resource;

    private ResourceMapping resourceMapping;

    private AllMapping allMapping;

    private LuceneSearchEngine searchEngine;

    private ArrayList<Token> tokens = new ArrayList<Token>();

    private AllTokenStreamCollector allTokenStreamCollector = new AllTokenStreamCollector();

    private boolean boostSupport;

    public AllAnalyzer(Analyzer analyzer, InternalResource resource, LuceneSearchEngine searchEngine) {
        this.analyzer = analyzer;
        this.resource = resource;
        this.resourceMapping = resource.resourceKey().getResourceMapping();
        this.searchEngine = searchEngine;
        this.allMapping = resourceMapping.getAllMapping();
        this.boostSupport = searchEngine.getSearchEngineFactory().getLuceneSettings().isAllPropertyBoostSupport();

        if (!allMapping.isSupported()) {
            return;
        }

        if (!allMapping.isExcludeAlias()) {
            // add the alias to all prpoerty (lowecased, so finding it will be simple)
            tokens.add(new Token(resource.getAlias().toLowerCase(), 0, resource.getAlias().length()));
            // add the extended property
            Property[] properties = resource.getProperties(searchEngine.getSearchEngineFactory().getExtendedAliasProperty());
            if (properties != null) {
                for (Property property : properties) {
                    tokens.add(new Token(property.getStringValue().toLowerCase(), 0, property.getStringValue().length()));
                }
            }
        }

        // go over all the un tokenized properties and add them as tokens (if required)
        // they are added since they will never get analyzed thus tokenStream will never
        // be called on them
        for (Property property : resource.getProperties()) {
            ResourcePropertyMapping resourcePropertyMapping = ((InternalProperty) property).getPropertyMapping();
            // if not found within the property, try and get it based on the name from the resource mapping
            if (resourcePropertyMapping == null) {
                resourcePropertyMapping = resourceMapping.getResourcePropertyMapping(property.getName());
            }
            if (resourcePropertyMapping == null) {
                if (searchEngine.getSearchEngineFactory().getPropertyNamingStrategy().isInternal(property.getName())) {
                    continue;
                }
                if (property.getName().equals(searchEngine.getSearchEngineFactory().getAliasProperty())) {
                    continue;
                }
                if (property.getName().equals(searchEngine.getSearchEngineFactory().getExtendedAliasProperty())) {
                    continue;
                }
                // no mapping, need to add un_tokenized ones
                if (property.isIndexed() && !property.isTokenized()) {
                    Payload payload = null;
                    if (boostSupport) {
                        if (property.getBoost() != 1.0f) {
                            payload = AllBoostUtils.writeFloat(property.getBoost());
                        } else if (resource.getBoost() != 1.0f) {
                            // we get the boost from the resource thus taking into account any resource property mapping
                            // and/or resource mapping boost level
                            payload = AllBoostUtils.writeFloat(resource.getBoost());
                        }
                    }
                    String value = property.getStringValue();
                    if (value != null) {
                        Token t = new Token(value, 0, value.length());
                        t.setPayload(payload);
                        tokens.add(t);
                    }
                }
                continue;
            }
            if (resourcePropertyMapping.isInternal()) {
                continue;
            }
            if (resourcePropertyMapping.getExcludeFromAll() == ResourcePropertyMapping.ExcludeFromAllType.YES) {
                continue;
            }
            if (resourcePropertyMapping.getIndex() == Property.Index.UN_TOKENIZED) {
                Payload payload = null;
                if (boostSupport) {
                    if (resourcePropertyMapping.getBoost() != 1.0f) {
                        payload = AllBoostUtils.writeFloat(resourcePropertyMapping.getBoost());
                    } else if (resource.getBoost() != 1.0f) {
                        // we get the boost from the resource thus taking into account any resource property mapping
                        // and/or resource mapping boost level
                        payload = AllBoostUtils.writeFloat(resource.getBoost());
                    }
                }
                String value = property.getStringValue();
                if (value != null) {
                    // if NO exclude from all, just add it
                    // if NO_ANALYZED, will analyze it as well
                    if (resourcePropertyMapping.getExcludeFromAll() == ResourcePropertyMapping.ExcludeFromAllType.NO) {
                        Token t = new Token(value, 0, value.length());
                        t.setPayload(payload);
                        tokens.add(t);
                    } else
                    if (resourcePropertyMapping.getExcludeFromAll() == ResourcePropertyMapping.ExcludeFromAllType.NO_ANALYZED) {
                        Analyzer propAnalyzer;
                        if (resourcePropertyMapping.getAnalyzer() != null) {
                            propAnalyzer = searchEngine.getSearchEngineFactory()
                                    .getAnalyzerManager().getAnalyzerMustExist(resourcePropertyMapping.getAnalyzer());
                        } else {
                            propAnalyzer = searchEngine.getSearchEngineFactory().getAnalyzerManager().getAnalyzerByResource(resource);
                        }
                        TokenStream ts = propAnalyzer.tokenStream(property.getName(), new StringReader(value));
                        try {
                            Token token = ts.next();
                            while (token != null) {
                                token.setPayload(payload);
                                tokens.add(token);
                                token = ts.next();
                            }
                        } catch (IOException e) {
                            throw new SearchEngineException("Failed to analyzer " + property, e);
                        }
                    }
                }
            }
        }
    }

    public TokenStream tokenStream(String fieldName, Reader reader) {
        TokenStream retVal = analyzer.tokenStream(fieldName, reader);
        return wrapTokenStreamIfNeeded(fieldName, retVal);
    }

    public TokenStream reusableTokenStream(String fieldName, Reader reader) throws IOException {
        TokenStream retVal = analyzer.reusableTokenStream(fieldName, reader);
        return wrapTokenStreamIfNeeded(fieldName, retVal);
    }

    public int getPositionIncrementGap(String fieldName) {
        return analyzer.getPositionIncrementGap(fieldName);
    }

    public TokenStream createAllTokenStream() {
        return new AllTokenStream();
    }

    private TokenStream wrapTokenStreamIfNeeded(String fieldName, TokenStream retVal) {
        if (!allMapping.isSupported()) {
            return retVal;
        }
        ResourcePropertyMapping resourcePropertyMapping = resourceMapping.getResourcePropertyMapping(fieldName);
        if (resourcePropertyMapping == null) {
            if (!searchEngine.getSearchEngineFactory().getPropertyNamingStrategy().isInternal(fieldName)) {
                if (allMapping.isIncludePropertiesWithNoMappings()) {
                    allTokenStreamCollector.setTokenStream(retVal);
                    allTokenStreamCollector.updateMapping(resource, resourcePropertyMapping);
                    retVal = allTokenStreamCollector;
                }
            }
        } else if (!(resourcePropertyMapping.getExcludeFromAll() == ResourcePropertyMapping.ExcludeFromAllType.YES)
                && !resourcePropertyMapping.isInternal()) {
            allTokenStreamCollector.setTokenStream(retVal);
            allTokenStreamCollector.updateMapping(resource, resourcePropertyMapping);
            retVal = allTokenStreamCollector;
        }
        return retVal;
    }

    /**
     * The all token stream. To be used with the all property as its token stream. This stream will
     * return all the tokens created and collected by this analyzer.
     */
    private class AllTokenStream extends TokenStream {

        private Iterator<Token> tokenIt;

        private int offset = 0;

        private AllTokenStream() {
        }

        /**
         * Override the next with token so no unneeded token will be created. Also,
         * no need to use the result, just return the token we saved where we just
         * change offests.
         */
        public Token next(Token result) throws IOException {
            if (tokenIt == null) {
                tokenIt = tokens.iterator();
            }
            if (tokenIt.hasNext()) {
                Token token = tokenIt.next();
                int delta = token.endOffset() - token.startOffset();
                token.setStartOffset(offset);
                offset += delta;
                token.setEndOffset(offset);
                return token;
            }

            tokens.clear();
            return null;
        }

        public String toString() {
            return "all-stream";
        }
    }

    /**
     * A token stream that wraps the actual token stream and collects all the
     * tokens it produces.
     */
    private class AllTokenStreamCollector extends TokenStream {

        private TokenStream tokenStream;

        private Payload payload;

        private Token lastToken;

        public AllTokenStreamCollector() {

        }

        public void updateMapping(InternalResource resource, ResourcePropertyMapping resourcePropertyMapping) {
            if (lastToken != null && payload != null) {
                lastToken.setPayload(payload);
                lastToken = null;
            }
            if (boostSupport) {
                if (resourcePropertyMapping != null && resourcePropertyMapping.getBoost() != 1.0f) {
                    payload = AllBoostUtils.writeFloat(resourcePropertyMapping.getBoost());
                } else if (resource.getBoost() != 1.0f) {
                    // we get the boost from the resource thus taking into account any resource property mapping
                    // and/or resource mapping boost level
                    payload = AllBoostUtils.writeFloat(resource.getBoost());
                } else {
                    payload = null;
                }
            }
        }

        public void setTokenStream(TokenStream tokenStream) {
            this.tokenStream = tokenStream;
        }

        public Token next() throws IOException {
            // we put the payload on the last token. It has already been indexed
            // and it will be used on the all property later on
            if (lastToken != null && payload != null) {
                lastToken.setPayload(payload);
            }
            lastToken = tokenStream.next();
            if (lastToken != null) {
                tokens.add(lastToken);
            }
            return lastToken;
        }

        public void reset() throws IOException {
            tokenStream.reset();
        }

        public void close() throws IOException {
            if (lastToken != null && payload != null) {
                lastToken.setPayload(payload);
            }
            tokenStream.close();
        }
    }
}
