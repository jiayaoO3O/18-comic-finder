package vip.comic18.finder.entity;

import lombok.Data;

import java.io.Serializable;

/**
 * Created by jiayao on 2021/2/16.
 */
@Data
public class PhotoEntity implements Serializable {
    private String name;
    private String url;
}
