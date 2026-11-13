package com.codrive.vehicle.model;

import java.util.List;

public final class Playlist {
    private final List<String> titles;
    private int index;

    public Playlist(List<String> titles) {
        if (titles.isEmpty()) {
            throw new IllegalArgumentException("empty playlist");
        }
        this.titles = titles;
    }

    public int index() {
        return index;
    }

    public String current() {
        return titles.get(index);
    }

    public void select(int i) {
        if (i < 0 || i >= titles.size()) {
            throw new IndexOutOfBoundsException();
        }
        index = i;
    }

    public String next() {
        index = (index + 1) % titles.size();
        return current();
    }

    public String previous() {
        index = (index + titles.size() - 1) % titles.size();
        return current();
    }
}
