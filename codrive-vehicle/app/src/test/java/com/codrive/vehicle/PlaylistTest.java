package com.codrive.vehicle;

import static org.junit.Assert.assertEquals;

import com.codrive.vehicle.model.Playlist;

import org.junit.Test;

import java.util.Arrays;

public class PlaylistTest {
    @Test
    public void nextWrapsAround() {
        Playlist p = new Playlist(Arrays.asList("A", "B", "C"));
        assertEquals("B", p.next());
        assertEquals("C", p.next());
        assertEquals("A", p.next());
    }

    @Test
    public void previousWrapsAround() {
        Playlist p = new Playlist(Arrays.asList("A", "B", "C"));
        assertEquals("C", p.previous());
        assertEquals(2, p.index());
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyListIsRejected() {
        new Playlist(Arrays.asList());
    }
}
