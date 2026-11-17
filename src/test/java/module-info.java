/**
 * The tests, as a module of their own that uses the library the way an application module does: it requires the
 * library and reaches only its exported API. Open, so that JUnit can reach the test classes.
 */
@SuppressWarnings("requires-automatic") // fdb-java, the reference implementation, has no module descriptor
open module io.descoped.tuple.test {
    requires io.descoped.tuple;
    requires org.junit.jupiter.api;
    requires org.junit.jupiter.params;
    requires fdb.java;
}
