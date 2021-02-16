package vip.comic18.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.comic18.finder.entity.ChapterEntity;
import vip.comic18.finder.entity.ComicEntity;
import vip.comic18.finder.entity.PhotoEntity;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by jiayao on 2021/2/16.
 */
@Service
@Slf4j
public class ComicService {

    @Value("${comic.download.path}")
    private String downloadPath;

    @Value("${comic.proxy.host}")
    private String proxyHost;

    @Value("${comic.proxy.port}")
    private int proxyPort;

    @Value("${comic.download.cookie}")
    private String cookie;

    private HttpRequest post = HttpUtil.createPost("https://18comic.vip");

    /**
     * 下载漫画到本地
     * 漫画会在下载路径下按照漫画名-章节名-图片名归类
     *
     * @param comicEntity 漫画信息
     */
    public void downloadComic(ComicEntity comicEntity) {
        String title = comicEntity.getTitle();
        File comicDir = FileUtil.mkdir(downloadPath + File.separatorChar + title);
        for(ChapterEntity chapter : comicEntity.getChapters()) {
            File chapterDir = FileUtil.file(comicDir.getPath() + File.separatorChar + chapter.getName());
            if(chapterDir == null || !chapterDir.exists()) {
                chapterDir = FileUtil.mkdir(comicDir.getPath() + File.separatorChar + chapter.getName());
            }
            List<PhotoEntity> photos = chapter.getPhotos();
            Map<String, BufferedImage> imageMap = new HashMap<>();
            for(PhotoEntity photo : photos) {
                File photoFile = FileUtil.file(chapterDir.getPath() + File.separatorChar + photo.getName());
                if(photoFile.exists()) {
                    log.info("downloadComic->图片[{}]已下载,跳过该图片", photoFile.getName());
                    continue;
                }
                BufferedImage image = this.getImage(photo.getUrl());
                if(chapter.getUpdatedAt().after(DateUtil.parse("2020-10-27"))) {
                    log.info("downloadComic->该章节:[{}]图片:[{}]需要进行反爬虫处理", chapter.getName(), photo.getName());
                    image = this.reverseImage(image);
                }
                log.info("downloadComic->成功下载图片:[{}]", chapterDir.getPath() + File.separatorChar + photo.getName());
                imageMap.put(chapterDir.getPath() + File.separatorChar + photo.getName(), image);
            }
            this.saveImage(imageMap);
        }
    }

    /**
     * 获取整本漫画的所有信息
     *
     * @param comicHomePage 漫画的主页
     * @return 漫画信息
     */
    public ComicEntity getComicInfo(String comicHomePage) {
        ComicEntity comicEntity = new ComicEntity();
        HttpResponse httpResponse = post.setUrl(comicHomePage).cookie(cookie).setHttpProxy(proxyHost, proxyPort).execute();
        String body = httpResponse.body();
        String title = StrUtil.subBetween(body, "<div itemprop=\"name\" class=\"pull-left\">\n", "\n</div>");
        title = StrUtil.replaceChars(title, new char[]{'/', '\\'}, StrUtil.DASHED);
        comicEntity.setTitle(title);
        comicEntity.setChapters(this.getChapterInfo(body, comicHomePage));
        log.info(comicEntity.toString());
        return comicEntity;
    }


    /**
     * @param body          html请求的结果信息
     * @param comicHomePage 漫画主页
     * @return 章节列表
     */
    private List<ChapterEntity> getChapterInfo(String body, String comicHomePage) {
        List<ChapterEntity> chapterEntities = new ArrayList<>();
        String host = "https://" + StrUtil.subBetween(comicHomePage, "//", "/");
        body = StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>");
        String[] chapters = StrUtil.subBetweenAll(body, "<a ", "</li>");
        for(String chapter : chapters) {
            ChapterEntity chapterEntity = new ChapterEntity();
            chapterEntity.setUrl(host + StrUtil.subBetween(chapter, "href=\"", "\""));
            chapter = StrUtil.removeAll(chapter, '\n');
            String[] nameAndDate = StrUtil.subBetweenAll(chapter, ">", "<");
            chapterEntity.setName(StrUtil.replaceChars(nameAndDate[ 0 ], new char[]{'/', '\\'}, StrUtil.DASHED));
            chapterEntity.setUpdatedAt(DateUtil.parse(nameAndDate[ nameAndDate.length - 1 ]));
            chapterEntity.setPhotos(this.getPhotoInfo(chapterEntity.getUrl()));
            chapterEntities.add(chapterEntity);
            log.info(chapterEntity.toString());
        }
        return chapterEntities;
    }


    private List<PhotoEntity> getPhotoInfo(String chapterUrl) {
        List<PhotoEntity> photoEntities = new ArrayList<>();
        HttpResponse httpResponse = null;
        while(httpResponse == null) {
            ThreadUtil.sleep(RandomUtil.randomInt(1, 5) * 1000L);
            try {
                httpResponse = post.setUrl(chapterUrl).cookie(cookie).setHttpProxy(proxyHost, proxyPort).execute();
            } catch(Exception e) {
                log.error("getPhotoInfo->获取图片信息失败:[{}]", e.getLocalizedMessage(), e);
            }
        }
        String body = httpResponse.body();
        body = StrUtil.subBetween(body, "<div class=\"row thumb-overlay-albums\" style=\"\">", "<div class=\"tab-content");
        String[] photos = StrUtil.subBetweenAll(body, "data-original=\"", "\" class=");
        for(String photo : photos) {
            PhotoEntity photoEntity = new PhotoEntity();
            photo = StrUtil.removeAll(photo, " id=\"");
            photoEntity.setUrl(StrUtil.split(photo, "\"")[ 0 ]);
            photoEntity.setName(StrUtil.split(photo, "\"")[ 1 ]);
            photoEntities.add(photoEntity);
            log.info(photoEntity.toString());
        }
        return photoEntities;
    }

    private BufferedImage getImage(String url) {
        HttpResponse httpResponse = null;
        while(httpResponse == null) {
            try {
                ThreadUtil.sleep(RandomUtil.randomInt(1, 5) * 1000L);
                httpResponse = post.setUrl(url).cookie(cookie).setHttpProxy(proxyHost, proxyPort).execute();
            } catch(Exception e) {
                log.error("getImage->下载图片失败:[{}]", e.getLocalizedMessage(), e);
            }
        }
        return ImgUtil.read(httpResponse.bodyStream());

    }


    /**
     * 对反爬虫照片进行重新排序
     * 禁漫天堂对2020-10-27之后的漫画都进行了反爬虫设置,图片顺序会被打乱,需要进行重新切割再组合
     * 例如https://cdn-msp.18comic.org/media/photos/235900/00028.jpg?v=1613363352
     *
     * @param bufferedImage 需要被切割重排的照片
     * @return 重新排序后的照片
     */
    private BufferedImage reverseImage(BufferedImage bufferedImage) {
        int height = bufferedImage.getHeight();
        int width = bufferedImage.getWidth();
        int preImgHeight = height / 10;
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        for(int i = 0; i < 10; i++) {
            BufferedImage subimage = bufferedImage.getSubimage(0, i * preImgHeight, width, preImgHeight);
            graphics.drawImage(subimage, null, 0, height - (i + 1) * preImgHeight);
        }
        return result;
    }


    private void saveImage(Map<String, BufferedImage> imageMap) {
        for(Map.Entry<String, BufferedImage> imageEntry : imageMap.entrySet()) {
            String image = imageEntry.getKey();
            log.info("saveImage->开始保存图片:[{}]", image);
            try {
                ImageIO.write(imageEntry.getValue(), "jpg", FileUtil.file(image));
            } catch(IOException e) {
                log.error("savePhoto->{}", e.getLocalizedMessage(), e);
            }
        }
    }
}
