package io.github.jiayaoO3O.finder.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.MD5;
import cn.hutool.log.Log;
import io.github.jiayaoO3O.finder.entity.ChapterEntity;
import io.github.jiayaoO3O.finder.entity.PhotoEntity;
import io.quarkus.runtime.Quarkus;
import io.smallrye.common.constraint.NotNull;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Named;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Created by jiayao on 2021/3/23.
 */
@ApplicationScoped
public class TaskService {
    private static final Log log = Log.get();

    @ConfigProperty(name = "comic.request.cookie")
    Optional<String> cookie;

    @ConfigProperty(name = "quarkus.log.file.path")
    String logPath;

    @Inject
    Vertx vertx;

    @Inject
    @Named("webClient")
    WebClient webClient;

    LongAdder pendingPhotoCount = new LongAdder();

    LongAdder processedPhotoCount = new LongAdder();

    private static final MD5 MD5 = cn.hutool.crypto.digest.MD5.create();

    private static final int[] rule = new int[]{2, 4, 6, 8, 10, 12, 14, 16, 18, 20};

    /**
     * @param body     漫画首页的html内容.
     * @param homePage 漫画首页url.
     * @return 返回一本漫画的所有章节内容.
     */
    public Multi<ChapterEntity> getChapterInfo(String body, String homePage) {
        var chapterEntities = new ArrayList<ChapterEntity>();
        var host = "https://" + StrUtil.subBetween(homePage, "//", "/");
        if(StrUtil.subBetween(body, "<ul class=\"btn-toolbar", "</ul>") == null) {
            //说明该漫画是单章漫画,没有区分章节,例如王者荣耀图鉴类型的https://18comic.vip/album/203961
            var url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", "開始閱讀"), "href=\"", "/\"");
            if(StrUtil.isEmpty(url)) {
                url = StrUtil.subBetween(StrUtil.subBetween(body, ">收藏<", "開始閱讀"), "href=\"", "\"");
            }
            if(StrUtil.isEmpty(url)) {
                log.error(StrUtil.format("获取章节信息失败->解析漫画url为空,程序退出"));
                Quarkus.asyncExit();
            }
            var name = StrUtil.removeAny(StrUtil.splitTrim(this.removeIllegalCharacter(StrUtil.subBetween(body, "<h1>", "</h1>")), " ")
                    .toString(), "[", "]", ",");
            String id = StrUtil.subAfter(url, '/', true);
            while(StrUtil.endWith(name, '.')) {
                name = StrUtil.removeSuffix(name, ".");
            }
            var chapterEntity = new ChapterEntity(Integer.parseInt(id), name, host + url);
            chapterEntities.add(chapterEntity);
            log.info(chapterEntity.toString());
            return Multi.createFrom()
                    .iterable(chapterEntities);
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
            var name = StrUtil.removeAny(StrUtil.splitTrim(this.removeIllegalCharacter(nameAndDate[ 0 ]), " ")
                    .toString(), "[", "]", ",");
            String id = StrUtil.subAfter(url, '/', true);
            while(StrUtil.endWith(name, '.')) {
                name = StrUtil.removeSuffix(name, ".");
            }
            var chapterEntity = new ChapterEntity(Integer.parseInt(id), name, host + url);
            chapterEntities.add(chapterEntity);
        }
        return Multi.createFrom()
                .iterable(chapterEntities);
    }

    /**
     * @param chapterEntity 漫画的某一个章节
     * @return 该章节对应的所有漫画图片信息
     */
    public Multi<PhotoEntity> getPhotoInfo(ChapterEntity chapterEntity) {
        return this.post(chapterEntity.url())
                .onItem()
                .transformToMulti(response -> {
                    var photoEntities = new ArrayList<PhotoEntity>();
                    var body = response.bodyAsString();
                    body = StrUtil.subBetween(body, "<div class=\"row thumb-overlay-albums\">", "<div class=\"tab-content");
                    var photos = StrUtil.subBetweenAll(body, "data-original=\"", "\" class=");
                    for(var photo : photos) {
                        if(StrUtil.containsAny(photo, ".jpg", ".webp", ".png")) {
                            photo = StrUtil.removeAll(photo, " id=\"");
                            var urlAndName = StrUtil.splitToArray(photo, "\"");
                            var photoEntity = new PhotoEntity(StrUtil.trim(urlAndName[ 1 ]), StrUtil.trim(urlAndName[ 0 ]));
                            photoEntities.add(photoEntity);
                            log.info(StrUtil.format("{}:chapter:[{}]-photo:[{}]-url:[{}]", this.clickPhotoCounter(true), chapterEntity.name(), photoEntity.name(), photoEntity.url()));
                        }
                    }
                    return Multi.createFrom()
                            .iterable(photoEntities);
                });
    }

    /**
     * @param tempFileTuple 临时文件信息
     */
    public Uni<Tuple2<String, Buffer>> writeTempFile(Tuple2<String, Buffer> tempFileTuple) {
        return this.write(tempFileTuple.getItem1(), tempFileTuple.getItem2())
                .chain(result -> Uni.createFrom()
                        .item(tempFileTuple));
    }

    public void writePhoto(int chapterId, String photoPath, Tuple2<String, Buffer> tuple2) {
        log.info(StrUtil.format("反爬处理->写入buffer到临时文件:[{}]成功", tuple2.getItem1()));
        BufferedImage bufferedImage = null;
        try(var inputStream = Files.newInputStream(Path.of(tuple2.getItem1()))) {
            bufferedImage = ImageIO.read(inputStream);
            if(bufferedImage == null) {
                log.error(StrUtil.format("反爬处理->捕获到bufferedImage为空:[{}],图片路径:[{}]", tuple2.getItem1(), photoPath));
            } else {
                this.write(photoPath, this.reverse(bufferedImage, chapterId, photoPath));
            }
        } catch(IOException e) {
            log.error(StrUtil.format("反爬处理->创建newInputStream失败:[{}]", e.getLocalizedMessage()), e);
        }
        this.delete(tuple2.getItem1());
    }

    /**
     * 获取漫画和临时文件路径的结合体
     * 因为函数的返回值只能返回一个对象,想要同时返回一个路径和buffer对象只能组合返回
     *
     * @param bufferUni 漫画图片buffer对象
     * @return 临时文件的路径和漫画buffer对象的结合体
     */
    public Uni<Tuple2<String, Buffer>> getTempFile(Buffer bufferUni) {
        var tempFile = this.createTempFile();
        return Uni.combine()
                .all()
                .unis(tempFile, Uni.createFrom()
                        .item(bufferUni))
                .asTuple();
    }

    public BufferedImage reverse(@NotNull BufferedImage bufferedImage, int chapterId, String photoPath) {
        //禁漫天堂最新的切割算法, 不再固定切割成10份, 而是需要通过chapterId和photoId共同确定分割块数.
        String photoId = "";
        if(photoPath.endsWith(".jpg")) {
            photoId = StrUtil.subBetween(photoPath, "photo_", ".jpg");
        }
        if(photoPath.endsWith(".webp")) {
            photoId = StrUtil.subBetween(photoPath, "photo_", ".webp");
        }
        int piece = 10;
        String md5;
        if(chapterId >= 268850) {
            md5 = MD5.digestHex(chapterId + photoId);
            char c = md5.charAt(md5.length() - 1);
            piece = rule[ c % 10 ];
            return this.reverseImage(bufferedImage, piece, true);
        } else {
            return this.reverseImage(bufferedImage, piece, false);
        }
    }

    /**
     * 对反爬虫照片进行重新排序.
     * 禁漫天堂对2020-10-27之后的漫画都进行了反爬虫设置, 图片顺序会被打乱, 需要进行重新切割再组合.
     * 例如https://cdn-msp.18comic.org/media/photos/235900/00028.jpg?v=1613363352
     *
     * @param bufferedImage 待反转的图片
     * @param piece         需要切割的块数
     * @return 已处理的图片
     */
    public BufferedImage reverseImage(BufferedImage bufferedImage, int piece, boolean newRule) {
        if(piece == 1) {
            return bufferedImage;
        }
        int height = bufferedImage.getHeight();
        int width = bufferedImage.getWidth();
        int preImgHeight = height / piece;
        if(preImgHeight == 0) {
            //如果分块后高度不足1像素说明不需要切割, 直接返回即可
            return bufferedImage;
        }
        if(!newRule) {
            var firstCheck = this.needReverse(bufferedImage, piece);
            if(firstCheck < piece / 2) {
                //第一次检查, 如果不相似数目少于一半, 则判断不用切割, 直接返回
                return bufferedImage;
            }
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = result.createGraphics();
            for(int i = 0; i < piece; i++) {
                BufferedImage subImage;
                if(i == piece - 1) {
                    //漫画的高度除以块数时,不一定是整数,此时漫画的第一块高度要算上剩余的像素.
                    subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, height - i * preImgHeight);
                    graphics.drawImage(subImage, null, 0, 0);
                } else {
                    subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, preImgHeight);
                    graphics.drawImage(subImage, null, 0, height - (i + 1) * preImgHeight);
                }
            }
            var doubleCheck = this.needReverse(result, piece);
            if(doubleCheck >= firstCheck) {
                //切割反转之后, 第二次检查, 如果不相似数目比第一次还多, 说明原本不用切, 切了反而错了
                return bufferedImage;
            }
            return result;
        } else {
            BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = result.createGraphics();
            for(int i = 0; i < piece; i++) {
                BufferedImage subImage;
                if(i == piece - 1) {
                    //漫画的高度除以块数时,不一定是整数,此时漫画的第一块高度要算上剩余的像素.
                    subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, height - i * preImgHeight);
                    graphics.drawImage(subImage, null, 0, 0);
                } else {
                    subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, preImgHeight);
                    graphics.drawImage(subImage, null, 0, height - (i + 1) * preImgHeight);
                }
            }
            return result;
        }
    }

    /**
     * @param url 发送请求的url
     * @return 请求结果
     */
    public Uni<HttpResponse<Buffer>> post(String url) {
        var request = webClient.getAbs(url)
                .port(443)
                .putHeader("Accept", "text/html,application/xhtml+xml,application/xml")
                .putHeader("Accept-Encoding", "deflate")
                .putHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6")
                .putHeader("Refer", url)
                .putHeader("cache-control", "no-cache")
                .putHeader("dnt", "1")
                .putHeader("upgrade-insecure-requests", " 1")
                .putHeader("pragma", " no-cache");
        //cookie.ifPresent(cook -> request.putHeader("cookie", cook));
        return request.send()
                .chain(response -> this.checkResponseStatus(url, response))
                .onFailure()
                .retry()
                .withBackOff(Duration.ofSeconds(4L))
                .atMost(10);
    }

    private Uni<HttpResponse<Buffer>> checkResponseStatus(String url, HttpResponse<Buffer> response) {
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
        if(StrUtil.contains(response.bodyAsString(), "Cloudflare")) {
            log.error(StrUtil.format("发送请求[{}]->发现cloudflare反爬虫验证, 正在进入重试:[We are checking your browser...]", url));
            throw new IllegalStateException("We are checking your browser...");
        }
        return Uni.createFrom()
                .item(response);
    }

    /**
     * @param path   保存到本地的文件路径
     * @param buffer 图片对象
     * @return 写入成功的信息
     */
    public Uni<Void> write(String path, Buffer buffer) {
        return vertx.fileSystem()
                .writeFile(path, buffer)
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("保存文件:[{}]失败:[{}]", path, e.getLocalizedMessage()), e));
    }

    public void delete(String path) {
        vertx.fileSystem()
                .delete(path)
                .onFailure()
                .invoke(e -> log.error(StrUtil.format("反爬处理->删除文件:[{}]失败:[{}]", path, e.getLocalizedMessage()), e))
                .subscribe()
                .with(succeed -> log.info(StrUtil.format("反爬处理->删除临时文件:[{}]", path)));
    }

    /**
     * @param path          写入文件路径
     * @param bufferedImage 图片buffer
     */
    public void write(String path, BufferedImage bufferedImage) {
        try(var outputStream = Files.newOutputStream(Path.of(path))) {
            ImageIO.write(bufferedImage, "jpg", outputStream);
            log.info(StrUtil.format("{}:保存文件成功:[{}]", this.clickPhotoCounter(false), path));
        } catch(IOException e) {
            log.error(StrUtil.format("{}:保存文件失败:[{}][{}]", this.clickPhotoCounter(false), path, e.getLocalizedMessage()), e);
        }
    }

    /**
     * 如果某一章节的漫画需要反爬处理, 则需要创建一个临时文件来接收需要处理的文件.
     *
     * @return 生成临时文件的路径.
     */
    public Uni<String> createTempFile() {
        return vertx.fileSystem()
                .createTempFile(String.valueOf(System.nanoTime()), ".tmp");
    }

    /**
     * @param body 网页的html内容.
     * @return 漫画的标题.
     */
    public String getTitle(String body) {
        String title = StrUtil.subBetween(body, "<h1>", "</h1>");
        title = this.removeIllegalCharacter(title);
        return title;
    }

    /**
     * 记录需要处理的图片数目和已经处理的图片数目.
     * pendingPhotoCount和processedPhotoCount初始值都为0.
     * 每当有一张图片需要下载, pendingPhotoCount加一;
     * 每当有一张图片已经处理完成, processedPhotoCount减一;
     * 到最后, 如果两者相加不为0, 说明还有图片未处理完成, 程序不能结束
     *
     * @param produce 是否是待处理的图片, true:图片待处理; false:照片已处理.
     * @return 返回目前待处理或者已处理的图片数目.
     */
    public String clickPhotoCounter(boolean produce) {
        if(produce) {
            this.pendingPhotoCount.increment();
            return StrUtil.format("生命周期检测->待处理页数:[{}]", this.pendingPhotoCount.longValue());
        } else {
            this.processedPhotoCount.decrement();
            return StrUtil.format("生命周期检测->已处理页数:[{}]", this.processedPhotoCount.longValue());
        }
    }

    /**
     * 前台模式的退出时机检测.
     * 一个异步程序什么时候能够结束运行是不好判断的, 因为所有的处理都是异步的, 返回了并不代表就已经结束了,
     * 所以这里的解决方案是间歇性读取日志文件, 如果发现日志文件长时间没有被修改,
     * 并且pendingPhotoCount和processedPhotoCount相加为0,
     * 那就说明程序已经完成任务了, 可以停止运行了.
     *
     * @return 程序是否应该退出.
     */
    public boolean exit() {
        var path = Path.of(logPath);
        var compareTo = 0;
        try {
            var lastModifiedTime = Files.getLastModifiedTime(path);
            compareTo = lastModifiedTime.compareTo(FileTime.from(DateUtil.toInstant(DateUtil.offsetSecond(DateUtil.date(), -32))));
        } catch(IOException e) {
            log.error(StrUtil.format("生命周期检测->读取日志错误:[{}]", e.getLocalizedMessage()), e);
        }
        return compareTo < 0 && this.processedPhotoCount.longValue() != this.pendingPhotoCount.longValue() && this.processedPhotoCount.longValue() != 0 && this.processedPhotoCount.longValue() + this.pendingPhotoCount.longValue() <= 10;
    }

    /**
     * github action中的upload插件对含有以下几种字符的文件路径视为非法路径, 会导致上传失败,
     * 所以当遇到标题有这些非法字符, 替换成横线-.
     *
     * @param name 传入的章节标题.
     * @return 清除非法字符之后的标题.
     */
    private String removeIllegalCharacter(String name) {
        name = StrUtil.replaceChars(name, new char[]{'/', '\\', ':', '*', '?', '"', '<', '>', '|'}, StrUtil.DASHED);
        name = StrUtil.trim(name);
        return name;
    }

    /**
     * 判断当页漫画是否需要切割
     * 先将漫画分块切割, 然后取前一块漫画块的最后一行像素, 与下一块漫画块的第一行像素, 进行相似度判断
     * 如果有超过一半数目的漫画块相似, 则判断为当页漫画各个块为连续, 不需要切割组合;
     * 如果有超过一半数目的漫画块不相似, 则判断为需要倒转重新组合
     *
     * @param bufferedImage 源图像
     * @param piece         切割块数
     * @return
     */
    public int needReverse(BufferedImage bufferedImage, int piece) {
        int height = bufferedImage.getHeight();
        int width = bufferedImage.getWidth();
        int preImgHeight = height / piece;
        List<BufferedImage> list = new ArrayList<>();
        for(int i = 0; i < piece; i++) {
            BufferedImage subImage;
            if(i == piece - 1) {
                //漫画的高度除以块数时,不一定是整数,此时漫画的第一块高度要算上剩余的像素.
                subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, height - i * preImgHeight);
                list.add(subImage);
            } else {
                subImage = bufferedImage.getSubimage(0, i * preImgHeight, width, preImgHeight);
                list.add(subImage);
            }
        }
        int result = 0;
        for(int i = 0; i < list.size() - 1; i++) {
            var firstBlock = list.get(i);
            var secondBlock = list.get(i + 1);
            var firstPixelLine = firstBlock.getSubimage(0, firstBlock.getHeight() - 1, width, 1);
            var secondPixelLine = secondBlock.getSubimage(0, 0, width, 1);
            String firstPixelLineCode = this.similarityDetection(firstPixelLine);
            String secondPixelLineCode = this.similarityDetection(secondPixelLine);
            char[] ch1 = firstPixelLineCode.toCharArray();
            char[] ch2 = secondPixelLineCode.toCharArray();
            int diffCount = 0;
            for(int k = 0; k < ch1.length - 1; k++) {
                if(ch1[ k ] != ch2[ k ]) {
                    //计算两者的汉明距离
                    diffCount++;
                }
            }
            if(diffCount > 16) {
                //按照经验, 如果两个漫画块连续, 则一般相差数目不会超过16
                result++;
            }
        }
        return result;
    }

    /**
     * 使用差异值哈希算法判断图像的相似度
     *
     * @param src 源图像
     * @return 对应的图像指纹
     */
    private String similarityDetection(BufferedImage src) {
        int width = src.getWidth();
        //高度固定为1像素
        int height = src.getHeight();
        //每间隔10像素抽取一个像素作为判断, 例如漫画宽度700则每隔10像素取一个点, 共70个点
        int pic = width / 10;
        int[][] ints = new int[ height ][ pic ];
        for(int i = 0; i < height; i++) {
            for(int j = 0; j < pic; j++) {
                int pixel = src.getRGB(j * 10, i);
                int gray = this.gray(pixel);
                ints[ i ][ j ] = gray;
            }
        }
        StringBuffer res = new StringBuffer();
        for(int i = 0; i < height; i++) {
            for(int j = 0; j < pic - 1; j++) {
                if(ints[ i ][ j ] >= ints[ i ][ j + 1 ]) {
                    res.append("1");
                } else {
                    res.append("0");
                }
            }
        }
        return res.toString();
    }

    /**
     * rgb-灰度转换
     *
     * @param rgb rgb值
     * @return 灰度值
     */
    private int gray(int rgb) {
        int a = rgb & 0xff000000;
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        rgb = (r * 77 + g * 151 + b * 28) >> 8;
        return a | (rgb << 16) | (rgb << 8) | rgb;
    }
}
