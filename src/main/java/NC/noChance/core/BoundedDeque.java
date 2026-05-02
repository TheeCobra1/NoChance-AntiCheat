package NC.noChance.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public class BoundedDeque<T> implements Iterable<T> {
    private final Deque<T> deque;
    private final int maxSize;

    public BoundedDeque(int maxSize) {
        this.maxSize = maxSize;
        this.deque = new ArrayDeque<>(maxSize);
    }

    public synchronized void add(T element) {
        if (deque.size() >= maxSize) {
            deque.poll();
        }
        deque.add(element);
    }

    public synchronized void addFirst(T element) {
        if (deque.size() >= maxSize) {
            deque.pollLast();
        }
        deque.addFirst(element);
    }

    public synchronized T poll() {
        return deque.poll();
    }

    public synchronized T pollLast() {
        return deque.pollLast();
    }

    public synchronized T peek() {
        return deque.peek();
    }

    public synchronized T peekLast() {
        return deque.peekLast();
    }

    public synchronized int size() {
        return deque.size();
    }

    public synchronized boolean isEmpty() {
        return deque.isEmpty();
    }

    public synchronized void clear() {
        deque.clear();
    }

    public synchronized Deque<T> getDeque() {
        return new ArrayDeque<>(deque);
    }

    public synchronized List<T> toList() {
        return new ArrayList<>(deque);
    }

    @Override
    public synchronized Iterator<T> iterator() {
        return new ArrayList<>(deque).iterator();
    }

    public synchronized boolean contains(T element) {
        return deque.contains(element);
    }

    public synchronized T[] toArray(T[] array) {
        return deque.toArray(array);
    }
}