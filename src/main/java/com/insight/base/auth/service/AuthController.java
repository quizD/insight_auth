package com.insight.base.auth.service;

import com.insight.base.auth.common.dto.LoginDTO;
import com.insight.util.ReplyHelper;
import com.insight.util.Util;
import com.insight.util.pojo.Reply;
import com.insight.util.service.BaseController;
import org.springframework.web.bind.annotation.*;

/**
 * @author 宣炳刚
 * @date 2017/12/18
 * @remark 租户服务控制器
 */
@CrossOrigin
@RestController
@RequestMapping("/base")
public class AuthController extends BaseController {
    private final AuthService service;

    /**
     * 构造方法
     *
     * @param service 自动注入的AuthService
     */
    public AuthController(AuthService service) {
        this.service = service;
    }

    /**
     * 获取Code
     *
     * @param account 用户登录账号
     * @param type    登录类型(0:密码登录、1:验证码登录)
     * @return Reply
     */
    @GetMapping("/v1.1/tokens/codes")
    public Reply getCode(@RequestParam String account, @RequestParam(defaultValue = "0") int type) {

        // 限流,每用户每日限定获取Code次数200次
        String key = Util.md5("getCode" + account + type);
        boolean limited = super.isLimited(key, 86400, 200);
        if (limited) {
            return ReplyHelper.fail("每日获取Code次数上限为200次，请合理利用");
        }

        return service.getCode(account, type);
    }

    /**
     * 获取Token
     *
     * @param userAgent 用户信息
     * @param login     用户登录数据
     * @return Reply
     */
    @GetMapping("/v1.1/tokens")
    public Reply getToken(@RequestHeader("User-Agent") String userAgent, LoginDTO login) {
        String appId = login.getAppId();
        if (appId == null || appId.isEmpty()){
            return ReplyHelper.invalidParam("appId不能为空");
        }

        return service.getToken(login, userAgent);
    }
}
