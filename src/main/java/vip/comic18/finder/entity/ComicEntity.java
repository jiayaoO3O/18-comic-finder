package vip.comic18.finder.entity;

import java.util.List;

/**
 * Created by jiayao on 2021/3/23.
 */
public class ComicEntity {
    private String title;

    public String getTitle() {
        return title;
    }

    @Override
    public String toString() {
        return "ComicEntity{" + "title='" + title + '\'' + ", chapters=" + chapters + '}';
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<ChapterEntity> getChapters() {
        return chapters;
    }

    public void setChapters(List<ChapterEntity> chapters) {
        this.chapters = chapters;
    }

    private List<ChapterEntity> chapters;
}
