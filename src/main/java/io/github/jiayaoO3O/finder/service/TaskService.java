package io.github.jiayaoO3O.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.github.jiayaoO3O.finder.entity.ChapterEntity;
import io.github.jiayaoO3O.finder.entity.PhotoEntity;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class TaskService {
    @ConfigProperty(name = "comic.request.cookie")
    Optional<String> cookie;

    @ConfigProperty(name = "quarkus.log.handler.file.\"INFO_LOG\".path")
    String logPath;

    @Inject
    Logger log;

    @Inject
    Vertx vertx;

    @Inject
    @Named("webClient")
    WebClient webClient;

    AtomicInteger pendingPhotoCount = new AtomicInteger(0);

    AtomicInteger processedPhotoCount = new AtomicInteger(0);

    public Multi<ChapterEntity> getChapterInfo(String body, String homePage) {
        var chapterEntities = new ArrayList<ChapterEntity>();
        var host = "https://" + StrUtil.subBetween(homePage, "//", "/");
        if(StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>") == null) {
            //说明该漫画是单章漫画,没有区分章节,例如王者荣耀图鉴类型的https://18comic.vip/album/203961
            var url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", ">開始閱讀<"), "href=\"", "/\"");
            var name = StrUtil.removeAny(StrUtil.splitTrim(this.removeIllegalCharacter(StrUtil.subBetween(body, "<h1>", "</h1>")), " ").toString(), "[", "]", ",");
            var updatedAt = DateUtil.parse(StrUtil.subBetween(StrUtil.subBetween(body, "itemprop=\"datePublished\"", "上架日期"), "content=\"", "\""));
            var chapterEntity = new ChapterEntity(name, host + url, updatedAt);
            chapterEntities.add(chapterEntity);
            log.info(chapterEntity.toString());
            return Multi.createFrom().iterable(chapterEntities);
        }
        body = StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>");
        var chapters = StrUtil.subBetweenAll(body, "<a ", "</li>");
        homePage = StrUtil.removeSuffix(homePage, "/");
        for(var chapter : chapters) {
            var url = StrUtil.subBetween(chapter, "href=\"", "\"");
            if(homePage.contains("photo") && !homePage.equals(host + url)) {
                continue;
            }
            chapter = StrUtil.removeAll(chapter, '\n', '\r');
            chapter = StrUtil.subAfter(chapter, "<li", false);
            var nameAndDate = StrUtil.subBetweenAll(chapter, ">", "<");
            var name = StrUtil.removeAny(StrUtil.splitTrim(this.removeIllegalCharacter(nameAndDate[ 0 ]), " ").toString(), "[", "]", ",");
            var updatedAt = DateUtil.parse(nameAndDate[ nameAndDate.length - 1 ]);
            var chapterEntity = new ChapterEntity(name, host + url, updatedAt);
            chapterEntities.add(chapterEntity);
        }
        return Multi.createFrom().iterable(chapterEntities);
    }

    public Multi<PhotoEntity> getPhotoInfo(ChapterEntity chapterEntity) {
        return this.createGet(chapterEntity.url()).onItem().transformToMulti(response -> {
            var photoEntities = new ArrayList<PhotoEntity>();
            var body = response.bodyAsString();
            if(StrUtil.contains(body, "禁漫娘被你玩壞啦")) {
                log.info(body);
            }
            body = StrUtil.subBetween(body, "<div class=\"row thumb-overlay-albums\" style=\"\">", "<div class=\"tab-content");
            var photos = StrUtil.subBetweenAll(body, "data-original=\"", "\" class=");
            for(var photo : photos) {
                if(StrUtil.contains(photo, ".jpg")) {
                    photo = StrUtil.removeAll(photo, " id=\"");
                    var urlAndName = StrUtil.split(photo, "\"");
                    var photoEntity = new PhotoEntity(StrUtil.trim(urlAndName[ 1 ]), StrUtil.trim(urlAndName[ 0 ]));
                    photoEntities.add(photoEntity);
                    this.addPendingPhotoCount();
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
        tempFile.onFailure(e -> e.getLocalizedMessage().contains("abc")).retry().atMost(3);
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
        this.createGet(url).subscribe().with(response -> {
            log.info(StrUtil.format("getAndSaveImage->成功下载图片:[{}]", url));
            this.write(photoPath, response.body()).subscribe().with(succeed -> {
                log.info(StrUtil.format("getAndSaveImage->保存文件成功:[{}]", photoPath));
                this.addProcessedPhotoCount();
            });
        });
    }

    public Uni<HttpResponse<Buffer>> createGet(String url) {
        var request = webClient.getAbs(url).port(443).followRedirects(true);
        cookie.ifPresent(cook -> request.putHeader("cookie", cook));
        return request.send().onItem().transform(response -> {
            if(StrUtil.contains(response.bodyAsString(), "休息一分鐘")) {
                log.error(StrUtil.format("发送请求[{}]->访问频率过高导致需要等待, 正在进入重试:[{}]", url, response.bodyAsString()));
                throw new IllegalStateException(response.bodyAsString());
            }
            if(StrUtil.contains(response.bodyAsString(), "Checking your browser before accessing")) {
                log.error(StrUtil.format("发送请求[{}]->发现反爬虫五秒盾, 正在进入重试:[Checking your browser before accessing]", url));
                throw new IllegalStateException("Checking your browser before accessing");
            }
            if(StrUtil.contains(response.bodyAsString(), "Please complete the security check to access")) {
                log.error(StrUtil.format("发送请求[{}]->发现cloudflare反爬虫验证, 正在进入重试:[Please complete the security check to access]", url));
                throw new IllegalStateException("Checking your browser before accessing");
            }
            if(StrUtil.contains(response.bodyAsString(), "Restricted Access")) {
                log.error(StrUtil.format("发送请求[{}]->访问受限, 正在进入重试:[Restricted Access!]", url));
                throw new IllegalStateException("Restricted Access!");
            }
            return response;
        }).onFailure().retry().withBackOff(Duration.ofSeconds(4L)).atMost(Long.MAX_VALUE).onFailure().invoke(e -> log.error(StrUtil.format("网络请求:[{}]失败:[{}]", url, e.getLocalizedMessage()), e));
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
            this.addProcessedPhotoCount();
        } catch(IOException e) {
            log.error(StrUtil.format("保存文件:[{}]失败:[{}]", path, e.getLocalizedMessage()), e);
        }
    }

    public Uni<String> createTempFile() {
        return vertx.fileSystem().createTempFile(String.valueOf(System.nanoTime()), ".tmp").onFailure().invoke(e -> log.error(StrUtil.format("创建临时文件失败:[{}]", e.getLocalizedMessage()), e));
    }

    public String getTitle(String body) {
        String title = StrUtil.subBetween(body, "<h1>", "</h1>");
        title = this.removeIllegalCharacter(title);
        return title;
    }

    public void addPendingPhotoCount() {
        log.info(StrUtil.format("生命周期检测->待处理照片数目为:[{}]", this.pendingPhotoCount.incrementAndGet()));
    }

    public void addProcessedPhotoCount() {
        log.info(StrUtil.format("生命周期检测->已处理照片数目为:[{}]", this.processedPhotoCount.decrementAndGet()));
    }

    public boolean exit() {
        /*
         前台模式的退出时机检测.
         一个异步程序什么时候能够结束运行是不好判断的, 因为所有的处理都是异步的, 不一定能够判断什么时候程序执行完成了,
         所以这里的解决方案是间歇性读取日志文件, 如果发现日志文件长时间没有被修改, 那就说明程序已经完成任务了, 可以停止运行了.
        */
        var path = Path.of(logPath);
        var compareTo = 0;
        try {
            var lastModifiedTime = Files.getLastModifiedTime(path);
            compareTo = lastModifiedTime.compareTo(FileTime.from(DateUtil.toInstant(DateUtil.offsetSecond(DateUtil.date(), -32))));
        } catch(IOException e) {
            log.error(StrUtil.format("exit->读取日志错误:[{}]", e.getLocalizedMessage()), e);
        }
        return compareTo < 0 && this.processedPhotoCount.get() != this.pendingPhotoCount.get() && this.processedPhotoCount.get() != 0 && this.processedPhotoCount.get() + this.pendingPhotoCount.get() == 0;
    }

    private String removeIllegalCharacter(String name) {
        name = StrUtil.replaceChars(name, new char[]{'/', '\\', ':', '*', '?', '"', '<', '>', '|'}, StrUtil.DASHED);
        name = StrUtil.trim(name);
        return name;
    }
}
