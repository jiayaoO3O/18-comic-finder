package vip.comic18.finder.entity;

import java.util.Date;
import java.util.List;

/**
 * Created by jiayao on 2021/3/23.
 */
public class ChapterEntity {
    private String name;

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "ChapterEntity{" + "name='" + name + '\'' + ", url='" + url + '\'' + ", photos=" + photos + ", updatedAt=" + updatedAt + '}';
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<vip.comic18.finder.entity.PhotoEntity> getPhotos() {
        return photos;
    }

    public void setPhotos(List<vip.comic18.finder.entity.PhotoEntity> photos) {
        this.photos = photos;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    private String url;
    private List<vip.comic18.finder.entity.PhotoEntity> photos;
    private Date updatedAt;
}
