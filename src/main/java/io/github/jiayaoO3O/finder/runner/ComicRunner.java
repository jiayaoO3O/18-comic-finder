package io.github.jiayaoO3O.finder.runner;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import io.github.jiayaoO3O.finder.service.ComicService;
import io.github.jiayaoO3O.finder.service.TaskService;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.List;

/**
 * Created by jiayao on 2021/3/23.
 */
@QuarkusMain
public class ComicRunner implements QuarkusApplication {
    private final List<String> comicHomePages = JSONUtil.toList(new ClassPathResource("downloadPath.json").readUtf8Str(), String.class);
    @Inject
    ComicService comicService;
    @Inject
    TaskService taskService;

    @Override
    public int run(String... args) {
        Log.info("注意身体,适度看漫");
        if(ArrayUtil.contains(args, "-s")) {
            Log.info("后台模式");
            Quarkus.waitForExit();
        }
        Log.info("前台模式");
        comicHomePages.addAll(Arrays.stream(ArrayUtil.filter(args, arg -> StrUtil.contains(arg, "http")))
                .toList());
        if(CollUtil.isEmpty(comicHomePages)) {
            Log.info("下载列表为空,终止任务");
            return 0;
        }
        comicHomePages.forEach(url -> comicService.getComicInfo(url)
                .subscribe()
                .with(body -> comicService.consumeComic(url, body)));
        while(!taskService.exit()) {
            ThreadUtil.sleep(8000L);
        }
        Log.info("任务结束,看漫愉快");
        return 0;
    }
}
