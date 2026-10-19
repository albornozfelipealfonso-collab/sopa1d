package cl.sopa1d.app;

public class Channel {
    public final String id;
    public final String name;
    public final String url;
    /** Logo y categoría: solo para el rótulo del reproductor (pueden ser ""). */
    public final String logo;
    public final String category;

    public Channel(String id, String name, String url) {
        this(id, name, url, "", "");
    }

    public Channel(String id, String name, String url, String logo, String category) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.logo = logo;
        this.category = category;
    }

    @Override
    public String toString() {
        return name;
    }
}
