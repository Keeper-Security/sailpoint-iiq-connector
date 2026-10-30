package com.keeper.identityiq;

import java.util.List;
import java.util.NoSuchElementException;

import sailpoint.object.ResourceObject;
import sailpoint.tools.CloseableIterator;

/** Iterator over an already-loaded list of objects. */
final class ResourceObjectIterator implements CloseableIterator<ResourceObject> {
    private final List<ResourceObject> objects;
    private int index;

    ResourceObjectIterator(List<ResourceObject> objects) {
        this.objects = objects;
    }

    @Override
    public boolean hasNext() {
        return index < objects.size();
    }

    @Override
    public ResourceObject next() {
        if (!hasNext()) throw new NoSuchElementException();
        return objects.get(index++);
    }

    @Override
    public void close() {
        index = objects.size();
    }
}
