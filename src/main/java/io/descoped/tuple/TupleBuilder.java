package io.descoped.tuple;

import java.util.ArrayList;
import java.util.List;

public class TupleBuilder {

    private final List<Tuple> elements;

    private TupleBuilder() {
        elements = new ArrayList<>();
    }

    public static TupleBuilder builder() {
        return new TupleBuilder();
    }

    public TupleBuilder add(String s) {
        elements.add(null);
        return this;
    }

    public Tuple pack() {
        // memoized size
        // encode elements
        byte[] encodedElements = new byte[elements.size()];

        return null;
    }
}
