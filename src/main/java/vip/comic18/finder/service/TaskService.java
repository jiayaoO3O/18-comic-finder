package vip.comic18.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.jboss.logging.Logger;
import vip.comic18.finder.entity.ChapterEntity;
import vip.comic18.finder.entity.PhotoEntity;

import javax.enterprise.context.ApplicationScoped;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class TaskService {

    @Inject
    Logger log;

    @Inject
    Vertx vertx;

    @Inject
    @Named("webClient")
    WebClient webClient;

    public Multi<ChapterEntity> getChapterInfo(String body, String comicHomePage) {
        var chapterEntities = new ArrayList<ChapterEntity>();
        var host = "https://" + StrUtil.subBetween(comicHomePage, "//", "/");
        if(StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>") == null) {
            //说明该漫画是单章漫画,没有区分章节,例如王者荣耀图鉴类型的https://18comic.vip/album/203961
            var url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", ">開始閱讀<"), "href=\"", "/\"");
            var name = StrUtil.removeAny(StrUtil.splitTrim(StrUtil.replaceChars(StrUtil.subBetween(body, "<h1>", "</h1>"), new char[]{'/', '\\', '|'}, StrUtil.DASHED), " ").toString(), "[", "]", ",");
            var updatedAt = DateUtil.parse(StrUtil.subBetween(StrUtil.subBetween(body, "itemprop=\"datePublished\"", "上架日期"), "content=\"", "\""));
            var chapterEntity = new ChapterEntity(name, host + url, updatedAt);
            chapterEntities.add(chapterEntity);
            log.info(chapterEntity.toString());
            return Multi.createFrom().iterable(chapterEntities);
        }
        body = StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>");
        var chapters = StrUtil.subBetweenAll(body, "<a ", "</li>");
        for(var chapter : chapters) {
            var url = StrUtil.subBetween(chapter, "href=\"", "\"");
            if(comicHomePage.contains("photo") && !comicHomePage.equals(host + url)) {
                continue;
            }
            chapter = StrUtil.removeAll(chapter, '\n', '\r');
            chapter = StrUtil.subAfter(chapter, "<li", false);
            var nameAndDate = StrUtil.subBetweenAll(chapter, ">", "<");
            var name = StrUtil.removeAny(StrUtil.splitTrim(StrUtil.replaceChars(nameAndDate[ 0 ], new char[]{'/', '\\', '|'}, StrUtil.DASHED), " ").toString(), "[", "]", ",");
            var updatedAt = DateUtil.parse(nameAndDate[ nameAndDate.length - 1 ]);
            var chapterEntity = new ChapterEntity(name, host + url, updatedAt);
            chapterEntities.add(chapterEntity);
            log.info(chapterEntity.toString());
        }
        return Multi.createFrom().iterable(chapterEntities);
    }

    public Multi<PhotoEntity> getPhotoInfo(ChapterEntity chapterEntity) {
        return this.get(chapterEntity.url()).onItem().transformToMulti(response -> {
            var photoEntities = new ArrayList<PhotoEntity>();
            var body = response.bodyAsString();
            body = StrUtil.subBetween(body, "<div class=\"row thumb-overlay-albums\" style=\"\">", "<div class=\"tab-content");
            var photos = StrUtil.subBetweenAll(body, "data-original=\"", "\" class=");
            for(var photo : photos) {
                if(StrUtil.contains(photo, ".jpg")) {
                    photo = StrUtil.removeAll(photo, " id=\"");
                    var urlAndName = StrUtil.split(photo, "\"");
                    var photoEntity = new PhotoEntity(StrUtil.trim(urlAndName[ 1 ]), StrUtil.trim(urlAndName[ 0 ]));
                    photoEntities.add(photoEntity);
                    log.info(StrUtil.format("chapter:[{}]-photo:[{}]-url:[{}]", chapterEntity.name(), photoEntity.name(), photoEntity.url()));
                }
            }
            return Multi.createFrom().iterable(photoEntities);
        });
    }

    public void process(String photoPath, Uni<Tuple2<String, Buffer>> tempFileTuple) {
        tempFileTuple.subscribe().with(tuple2 -> this.write(tuple2.getItem1(), tuple2.getItem2()).subscribe().with(succeed -> {
            log.info(StrUtil.format("写入buffer到临时文件:[{}]成功", tuple2.getItem1()));
            BufferedImage bufferedImage = null;
            try(var inputStream = Files.newInputStream(Path.of(tuple2.getItem1()))) {
                bufferedImage = ImageIO.read(inputStream);
                if(bufferedImage == null) {
                    log.error(StrUtil.format("捕获到bufferedImage为空:[{}],图片路径:[{}]", tuple2.getItem1(), photoPath));
                } else {
                    this.write(photoPath, this.reverseImage(bufferedImage));
                }
            } catch(IOException e) {
                log.error(StrUtil.format("getImage->创建newInputStream失败:[{}]", e.getLocalizedMessage()), e);
            }
            this.delete(tuple2.getItem1());
        }));
    }

    public Uni<Tuple2<String, Buffer>> getTempFile(Uni<Buffer> bufferUni) {
        var tempFile = this.createTempFile();
        return Uni.combine().all().unis(tempFile, bufferUni).asTuple();
    }

    /**
     * 对反爬虫照片进行重新排序
     * 禁漫天堂对2020-10-27之后的漫画都进行了反爬虫设置,图片顺序会被打乱,需要进行重新切割再组合
     * 例如https://cdn-msp.18comic.org/media/photos/235900/00028.jpg?v=1613363352
     */
    public BufferedImage reverseImage(@NotNull BufferedImage bufferedImage) {
        int height = bufferedImage.getHeight();
        int width = bufferedImage.getWidth();
        int preImgHeight = height / 10;
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        for(int i = 0; i < 10; i++) {
            BufferedImage subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, preImgHeight);
            graphics.drawImage(subImage, null, 0, height - (i + 1) * preImgHeight);
        }
        return result;
    }

    public void getAndSaveImage(String url, String photoPath) {
        this.get(url).subscribe().with(response -> {
            log.info(StrUtil.format("getAndSaveImage->成功下载图片:[{}]", url));
            this.write(photoPath, response.body()).subscribe().with(succeed -> log.info(StrUtil.format("getAndSaveImage->保存文件成功:[{}]", photoPath)));
        });
    }

    public Uni<HttpResponse<Buffer>> get(String url) {
        return webClient.getAbs(url).port(443).followRedirects(true).send().onFailure().retry().withBackOff(Duration.ofSeconds(1L), Duration.ofSeconds(3L)).atMost(10).onFailure().invoke(e -> log.error(StrUtil.format("网络请求:[{}]失败:[{}]", url, e.getLocalizedMessage()), e));
    }

    public Uni<Void> write(String path, Buffer buffer) {
        return vertx.fileSystem().writeFile(path, buffer).onFailure().invoke(e -> log.error(StrUtil.format("保存文件:[{}]失败:[{}]", path, e.getLocalizedMessage()), e));
    }

    public void delete(String path) {
        vertx.fileSystem().delete(path).onFailure().invoke(e -> log.error(StrUtil.format("删除文件:[{}]失败:[{}]", path, e.getLocalizedMessage()), e)).subscribe().with(succeed -> log.info(StrUtil.format("删除临时文件:[{}]", path)));
    }

    public void write(String path, BufferedImage bufferedImage) {
        try(var outputStream = Files.newOutputStream(Path.of(path))) {
            ImageIO.write(bufferedImage, "jpg", outputStream);
            log.info(StrUtil.format("保存文件成功:[{}]", path));
        } catch(IOException e) {
            log.error(StrUtil.format("保存文件:[{}]失败:[{}]", path, e.getLocalizedMessage()), e);
        }
    }

    public Uni<String> createTempFile() {
        return vertx.fileSystem().createTempFile(String.valueOf(System.nanoTime()), ".tmp").onFailure().invoke(e -> log.error(StrUtil.format("创建临时文件失败:[{}]", e.getLocalizedMessage()), e));
    }

    public String getTitle(String body) {
        String title = StrUtil.subBetween(body, "<h1>", "</h1>");
        title = StrUtil.replaceChars(title, new char[]{'/', '\\', '|'}, StrUtil.DASHED);
        title = StrUtil.trim(title);
        return title;
    }
}
