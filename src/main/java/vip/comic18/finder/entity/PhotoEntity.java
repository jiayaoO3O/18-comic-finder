package vip.comic18.finder.entity;

/**
 * Created by jiayao on 2021/3/23.
 */
public class PhotoEntity {
    private String name;
    private String url;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    @Override
    public String toString() {
        return "PhotoEntity{" + "name='" + name + '\'' + ", url='" + url + '\'' + '}';
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
