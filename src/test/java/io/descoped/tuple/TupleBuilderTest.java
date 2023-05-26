package io.descoped.tuple;

import org.junit.jupiter.api.Test;

class TupleBuilderTest {

    /*
        TODO
            move constants to enum with startCode/endCode
            get enum type by instanceof
            move enum type to upper level
            use switch type encoder.func in TupleBuilder.pack()


     */

    @Test
    void testTuple() {
        TupleBuilder builder = TupleBuilder.builder();
        builder.add("a");

        Tuple tuple = builder.pack();
        byte[] bytes = tuple.toBytes();
    }
}
