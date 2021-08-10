package io.github.jiayaoO3O.finder.runner;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import io.github.jiayaoO3O.finder.service.ComicService;
import io.github.jiayaoO3O.finder.service.TaskService;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.jboss.logging.Logger;

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
    Logger log;
    @Inject
    ComicService comicService;
    @Inject
    TaskService taskService;

    @Override
    public int run(String... args) {
        log.info("注意身体,适度看漫");
        if(ArrayUtil.contains(args, "-s")) {
            log.info("后台模式");
            Quarkus.waitForExit();
        }
        log.info("前台模式");
        comicHomePages.addAll(Arrays.stream(ArrayUtil.filter(args, arg -> StrUtil.contains(arg, "http")))
                .toList());
        if(CollUtil.isEmpty(comicHomePages)) {
            log.info("下载列表为空,终止任务");
            return 0;
        }
        comicHomePages.forEach(comicHomePage -> comicService.getComicInfo(comicHomePage)
                .subscribe()
                .with(body -> comicService.consume(comicHomePage, body)));
        while(!taskService.exit()) {
            ThreadUtil.sleep(8000L);
        }
        log.info("任务结束,看漫愉快");
        return 0;
    }
}
