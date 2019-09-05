package com.insight.base.auth.common.entity;

/**
 * @author 宣炳刚
 * @date 2017/9/15
 * @remark 授权信息类
 */
public class ModuleInfo {

    /**
     * 图标
     */
    private String icon;

    /**
     * 图标路径
     */
    private String iconUrl;

    /**
     * 模块名称
     */
    private String module;

    /**
     * 模块文件
     */
    private String file;

    /**
     * 是否启动模块
     */
    private Boolean isDefault;

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public Boolean getDefault() {
        return isDefault;
    }

    public void setDefault(Boolean aDefault) {
        isDefault = aDefault;
    }
}
