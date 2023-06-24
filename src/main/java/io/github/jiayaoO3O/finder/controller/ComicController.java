package io.github.jiayaoO3O.finder.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.log.Log;
import io.github.jiayaoO3O.finder.service.ComicService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;


/**
 * Created by jiayao on 2021/3/23.
 */
@Path("/finder")
public class ComicController {
    private static final Log log = Log.get();

    @Inject
    ComicService comicService;

    @GET
    @Path("/download")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<String> download(@QueryParam("homePage") String homePage) {
        if(StrUtil.isEmpty(homePage)) {
            log.error("download->homePage为空");
            return Uni.createFrom()
                    .item("homePage为空");
        }
        if(!HttpUtil.isHttps(homePage) && !HttpUtil.isHttp(homePage)) {
            log.error(StrUtil.format("download->homePage参数:[{}]并非http或https链接", homePage));
            return Uni.createFrom()
                    .item(StrUtil.format("homePage参数:[{}]并非http或https链接", homePage));
        }
        comicService.processComic(homePage);
        return Uni.createFrom()
                .item(StrUtil.format("已经添加任务:[{}]", homePage));
    }
}
