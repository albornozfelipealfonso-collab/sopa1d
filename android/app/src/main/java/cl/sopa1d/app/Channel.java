package cl.sopa1d.app;

public class Channel {
    public final String id;
    public final String name;
    public final String url;

    public Channel(String id, String name, String url) {
        this.id = id;
        this.name = name;
        this.url = url;
    }

    @Override
    public String toString() {
        return name;
    }
}
