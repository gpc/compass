package org.compass.annotations.test.poly.operations;

import org.compass.annotations.test.AbstractAnnotationsTestCase;
import org.compass.core.CompassSession;
import org.compass.core.CompassTransaction;
import org.compass.core.config.CompassConfiguration;

/**
 * @author kimchy
 */
public class PolyOperationsTests extends AbstractAnnotationsTestCase {

    protected void addExtraConf(CompassConfiguration conf) {
        conf.addClass(Contract.class).addClass(A.class).addClass(B.class);
    }

    public void testGetPolyOperations() {
        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();

        A a = new A();
        a.id = 1;
        session.save(a);

        B b = new B();
        b.id = 2;
        session.save(b);

        a = session.get(A.class, 1);
        assertNotNull(a);

        a = session.get(B.class, 1);
        assertNull(a);

        a = session.get(B.class, 2);
        assertNotNull(a);

        a = session.get(A.class, 2);
        assertNotNull(a);

        Contract c = session.get(Contract.class, 2);
        assertNotNull(c);

        c = session.get(Contract.class, 1);
        assertNotNull(c);

        tr.commit();
        session.close();
    }

    public void testDeletePolyOperations() {
        CompassSession session = openSession();
        CompassTransaction tr = session.beginTransaction();

        A a = new A();
        a.id = 1;
        session.save(a);

        B b = new B();
        b.id = 2;
        session.save(b);

        a = new A();
        a.id = 3;
        session.save(a);

        a = session.get(A.class, 1);
        assertNotNull(a);

        a = session.get(B.class, 2);
        assertNotNull(a);

        a = session.get(A.class, 3);
        assertNotNull(a);

        session.delete(A.class, 2);
        a = session.get(B.class, 2);
        assertNull(a);

        session.delete(Contract.class, 1);
        a = session.get(A.class, 1);
        assertNull(a);


        tr.commit();
        session.close();
    }
}
