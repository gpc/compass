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

package org.compass.core.test.find;

import java.util.NoSuchElementException;

import org.apache.lucene.search.Explanation;
import org.compass.core.CompassDetachedHits;
import org.compass.core.CompassHit;
import org.compass.core.CompassHitIterator;
import org.compass.core.CompassHits;
import org.compass.core.CompassSession;
import org.compass.core.CompassTransaction;
import org.compass.core.Resource;
import org.compass.core.engine.SearchEngineException;
import org.compass.core.lucene.util.LuceneHelper;
import org.compass.core.test.AbstractTestCase;

/**
 * @author kimchy
 */
public class FindTests extends AbstractTestCase {

    protected String[] getMappings() {
        return new String[]{"find/find.cpm.xml"};
    }

    public void testIterator() {
        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();

        Long id1 = new Long(1);
        A a1 = new A();
        a1.setId(id1);
        a1.setValue("value 1");
        session.save(a1);
        Long id2 = new Long(2);
        A a2 = new A();
        a2.setId(id2);
        a2.setValue("value 2");
        session.save(a2);

        CompassHitIterator it = session.find("value").detach().iterator();
        assertEquals(id1, ((A) it.nextHit().getData()).getId());
        assertEquals(id2, ((A) it.nextHit().getData()).getId());
        try {
            it.nextHit();
            fail();
        } catch (NoSuchElementException e) {

        }

        tr.commit();
        session.close();
    }

    public void testDetach() {
        addDataA(0, 50);

        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        CompassHits hits = session.find("alias:a1");
        assertEquals(50, hits.getLength());
        CompassDetachedHits detachedHits = hits.detach(40, 10);
        tr.commit();
        session.close();

        assertEquals(10, detachedHits.getLength());
        assertEquals(50, detachedHits.totalLength());
        Resource r = detachedHits.resource(0);
        assertEquals("40", r.get("id"));
        assertEquals(new Long(40), ((A) detachedHits.data(0)).getId());

        CompassHit hit = detachedHits.hit(0);
        assertEquals("40", hit.getResource().get("id"));
        assertEquals(new Long(40), ((A) hit.getData()).getId());

        Resource[] resources = detachedHits.getResources();
        assertEquals(10, resources.length);

        Object[] datas = detachedHits.getDatas();
        assertEquals(10, datas.length);
    }

    public void testWithAsterix() {
        addDataA(0, 10);
        addDataB(0, 10);

        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        addDataA(10, 20);
        addDataB(10, 20);
        CompassHits hits = session.find("v*");
        assertEquals(40, hits.getLength());
        tr.commit();
        session.close();
    }

    public void testWithPrefix() {
        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        A a = new A();
        a.setId(new Long(1));
        a.setValue("20020101");
        session.save(a);
        CompassHits hits = session.find("mvalue:20020101");
        assertEquals(1, hits.getLength());
        tr.commit();
        session.close();
    }

    public void testSubIndexAliasNarrow() {
        addDataA(0, 10);
        addDataB(0, 10);

        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        CompassHits hits = session.find("alias:a1 or alias:b1");
        assertEquals(20, hits.getLength());

        hits = session.queryBuilder().queryString("alias:a1 or alias:b1").toQuery()
                .setAliases(new String[]{"a1"}).hits();
        assertEquals(10, hits.getLength());

        hits = session.queryBuilder().queryString("alias:a1 or alias:b1").toQuery()
                .setSubIndexes(new String[]{"a1"}).hits();
        assertEquals(10, hits.getLength());

        hits = session.queryBuilder().queryString("alias:a1 or alias:b1").toQuery()
                .setSubIndexes(new String[]{"a1"})
                .setAliases(new String[]{"a1"}).hits();
        assertEquals(10, hits.getLength());

        tr.commit();
        session.close();
    }

    public void testSubIndexTypeNarrow() {
        addDataA(0, 10);
        addDataB(0, 10);

        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        CompassHits hits = session.find("alias:a1 or alias:b1");
        assertEquals(20, hits.getLength());

        hits = session.queryBuilder().queryString("alias:a1 or alias:b1").toQuery()
                .setTypes(new Class[]{A.class}).hits();
        assertEquals(10, hits.getLength());

        hits = session.queryBuilder().queryString("alias:a1 or alias:b1").toQuery()
                .setSubIndexes(new String[]{"a1"}).hits();
        assertEquals(10, hits.getLength());

        hits = session.queryBuilder().queryString("alias:a1 or alias:b1").toQuery()
                .setSubIndexes(new String[]{"a1"})
                .setTypes(new Class[]{A.class}).hits();
        assertEquals(10, hits.getLength());

        tr.commit();
        session.close();
    }

    public void testLuceneExplanation() {
        addDataA(0, 10);

        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        CompassHits hits = session.find("alias:a1 or alias:b1");
        assertEquals(10, hits.getLength());

        Explanation explanation = LuceneHelper.getLuceneSearchEngineHits(hits).explain(0);
        assertNotNull(explanation);
        assertEquals("product of:", explanation.getDescription());

        tr.commit();
        session.close();
    }

    public void testExceptionOutsideOfATransaction() {
        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        CompassHits hits = session.find("alias:a1 or alias:b1");
        tr.commit();
        try {
            session.find("alias:a1 or alias:b1");
            fail();
        } catch (SearchEngineException e) {
            // all is well
        }
        session.close();
    }

    private void addDataA(int from, int to) {
        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        for (int i = from; i < to; i++) {
            A a = new A();
            a.setId(new Long(i));
            a.setValue("value");
            session.save(a);
        }
        tr.commit();
        session.close();
    }

    private void addDataB(int from, int to) {
        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();
        for (int i = from; i < to; i++) {
            B b = new B();
            b.setId(new Long(i));
            b.setValue("value");
            session.save(b);
        }
        tr.commit();
        session.close();
    }

}
