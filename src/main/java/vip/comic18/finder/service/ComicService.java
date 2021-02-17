package vip.comic18.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.comic18.finder.entity.ChapterEntity;
import vip.comic18.finder.entity.ComicEntity;
import vip.comic18.finder.entity.PhotoEntity;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Created by jiayao on 2021/2/16.
 */
@Service
@Slf4j
public class ComicService {
    @Autowired
    private AsyncTaskService taskService;

    @Value("${comic.download.path}")
    private String downloadPath;

    @Value("${comic.proxy.host}")
    private String proxyHost;

    @Value("${comic.proxy.port}")
    private int proxyPort;

    @Value("${comic.download.cookie}")
    private String cookie;


    /**
     * 下载漫画到本地
     * 漫画会在下载路径下按照漫画名-章节名-图片名归类
     *
     * @param comicEntity 漫画信息
     */
    public void downloadComic(ComicEntity comicEntity) throws ExecutionException, InterruptedException {
        String title = comicEntity.getTitle();
        File comicDir = FileUtil.mkdir(downloadPath + File.separatorChar + title);
        for(ChapterEntity chapter : comicEntity.getChapters()) {
            File chapterDir = FileUtil.file(comicDir.getPath() + File.separatorChar + chapter.getName());
            if(chapterDir == null || !chapterDir.exists()) {
                chapterDir = FileUtil.mkdir(comicDir.getPath() + File.separatorChar + chapter.getName());
            }
            List<PhotoEntity> photos = chapter.getPhotos();
            for(PhotoEntity photo : photos) {
                File photoFile = FileUtil.file(chapterDir.getPath() + File.separatorChar + photo.getName());
                if(photoFile.exists()) {
                    log.info("downloadComic->图片[{}]已下载,跳过该图片", photoFile.getName());
                    continue;
                }
                BufferedImage image = taskService.getImage(photo.getUrl()).get();
                if(chapter.getUpdatedAt().after(DateUtil.parse("2020-10-27"))) {
                    log.info("downloadComic->该章节:[{}]图片:[{}]需要进行反爬虫处理", chapter.getName(), photo.getName());
                    image = taskService.reverseImage(image).get();
                }
                taskService.saveImage(chapterDir.getPath() + File.separatorChar + photo.getName(), image);
            }
        }
    }

    /**
     * 获取整本漫画的所有信息
     *
     * @param comicHomePage 漫画的主页
     * @return 漫画信息
     */
    public ComicEntity getComicInfo(String comicHomePage) throws ExecutionException, InterruptedException {
        ComicEntity comicEntity = new ComicEntity();
        HttpResponse httpResponse = HttpUtil.createPost(comicHomePage).cookie(cookie).setHttpProxy(proxyHost, proxyPort).execute();
        String body = httpResponse.body();
        String title = StrUtil.subBetween(body, "<div itemprop=\"name\" class=\"pull-left\">\n", "\n</div>");
        title = StrUtil.replaceChars(title, new char[]{'/', '\\'}, StrUtil.DASHED);
        comicEntity.setTitle(title);
        List<ChapterEntity> chapterEntities = taskService.getChapterInfo(body, comicHomePage).get();
        for(ChapterEntity chapterEntity : chapterEntities) {
            chapterEntity.setPhotos(taskService.getPhotoInfo(chapterEntity).get());
        }
        comicEntity.setChapters(chapterEntities);
        log.info(comicEntity.toString());
        return comicEntity;
    }
}
