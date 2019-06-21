package com.insight.base.auth.common;

import com.insight.base.auth.common.dto.AuthInfo;
import com.insight.base.auth.common.dto.LoginDTO;
import com.insight.base.auth.common.dto.TokenPackage;
import com.insight.base.auth.common.dto.UserInfo;
import com.insight.base.auth.common.mapper.AuthMapper;
import com.insight.util.Generator;
import com.insight.util.Json;
import com.insight.util.Redis;
import com.insight.util.Util;
import com.insight.util.encrypt.Encryptor;
import com.insight.util.pojo.AccessToken;
import com.insight.util.pojo.Reply;
import com.insight.util.pojo.User;
import com.insight.utils.message.pojo.Sms;
import com.insight.utils.wechat.WeChatHelper;
import com.insight.utils.wechat.WeChatUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author 宣炳刚
 * @date 2017/9/7
 * @remark 用户身份验证核心类(组件类)
 */
@Component
public class Core {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final AuthMapper mapper;
    private final WeChatHelper weChatHelper;

    /**
     * RSA私钥
     */
    private static final String PRIVATE_KEY = "";

    /**
     * Code生命周期(30秒)
     */
    private static final int GENERAL_CODE_LEFT = 30;

    /**
     * 登录短信验证码有效时间(300秒)
     */
    private static final int SMS_CODE_LEFT = 300;

    /**
     * 构造函数
     *
     * @param mapper       AuthMapper
     * @param weChatHelper WeChatHelper
     */
    public Core(AuthMapper mapper, WeChatHelper weChatHelper) {
        this.mapper = mapper;
        this.weChatHelper = weChatHelper;
    }

    /**
     * 根据用户登录账号获取Account缓存中的用户ID
     *
     * @param account 登录账号(账号、手机号、E-mail、openId)
     * @return 用户ID
     */
    public String getUserId(String account) {
        String key = "ID:" + account;
        String userId = Redis.get(key);
        if (userId != null && !userId.isEmpty()) {
            return userId;
        }

        synchronized (this) {
            userId = Redis.get(account);
            if (userId != null && !userId.isEmpty()) {
                return userId;
            }

            User user = mapper.getUser(account);
            if (user == null) {
                return null;
            }

            // 缓存用户ID到Redis
            userId = user.getId();
            key = "ID:" + user.getAccount();
            Redis.set(key, userId);

            String mobile = user.getMobile();
            if (mobile != null && !mobile.isEmpty()) {
                key = "ID:" + mobile;
                Redis.set(key, userId);
            }

            String unionId = user.getUnionId();
            if (unionId != null && !unionId.isEmpty()) {
                key = "ID:" + unionId;
                Redis.set(key, userId);
            }

            String mail = user.getEmail();
            if (mail != null && !mail.isEmpty()) {
                key = "ID:" + mail;
                Redis.set(key, userId);
            }

            key = "User:" + userId;
            Redis.set(key, "User", Json.toJson(user));
            Redis.set(key, "IsInvalid", user.getInvalid().toString());
            Redis.set(key, "FailureCount", "0");

            String pw = user.getPassword();
            String password = pw.length() > 32 ? Encryptor.rsaDecrypt(pw, PRIVATE_KEY) : pw;
            Redis.set(key, "Password", password);

            return userId;
        }
    }

    /**
     * 根据登录账号生成Code
     *
     * @param userId   用户ID
     * @param account  登录账号
     * @param password 密码
     * @return Code
     */
    public String getGeneralCode(String userId, String account, String password) {
        String key = Util.md5(account + password);

        return generateCode(userId, key, GENERAL_CODE_LEFT);
    }

    /**
     * 根据手机号生成Code
     *
     * @param userId 用户ID
     * @param mobile 手机号
     * @return Code
     */
    public String getSmsCode(String userId, String mobile) {
        String smsCode = generateSmsCode(4, mobile, SMS_CODE_LEFT / 60, 4);
        String key = Util.md5(mobile + Util.md5(smsCode));
        Map<String, Object> map = new HashMap<>(16);
        map.put("code", smsCode);

        Reply reply = sendMessageSyn("SMS00005", mobile, map);
        if (reply == null || !reply.getSuccess()) {
            return "短信发送失败";
        }

        return generateCode(userId, key, SMS_CODE_LEFT);
    }

    /**
     * 生成令牌数据包
     *
     * @param code   Code
     * @param login  登录信息
     * @param userId 用户ID
     * @return 令牌数据包
     */
    public TokenPackage creatorToken(String code, LoginDTO login, String userId) {
        String appId = login.getAppId();
        String tenantId = login.getTenantId();
        String deptId = login.getDeptId();
        String fingerprint = login.getFingerprint();

        Token token = new Token(appId, tenantId, deptId);
        if (tenantId != null) {
            List<AuthInfo> funs = mapper.getAuthInfos(appId, userId, tenantId, deptId);
            List<String> list = funs.stream().filter(i -> i.getPermit() > 0).map(i -> {
                String urls = i.getInterfaces();
                String codes = i.getAuthCodes();
                return codes + (codes != null && urls != null ? "," : "") + urls;
            }).collect(Collectors.toList());
            token.setPermitFuncs(list);
        }

        return initPackage(token, code, fingerprint, userId, appId);
    }

    /**
     * 刷新Secret过期时间
     *
     * @param token       令牌
     * @param fingerprint 用户特征串
     * @param userId      用户ID
     * @return 令牌数据包
     */
    public TokenPackage refreshToken(Token token, String fingerprint, String userId) {
        token.refresh();
        long life = token.getLife() * 12;
        String appId = token.getAppId();
        String code = Generator.uuid();

        return initPackage(token, code, fingerprint, userId, appId);
    }

    /**
     * 初始化令牌数据包
     *
     * @param token       令牌数据
     * @param code        Code
     * @param fingerprint 用户特征串
     * @param userId      用户ID
     * @param appId       应用ID
     * @return 令牌数据包
     */
    private TokenPackage initPackage(Token token, String code, String fingerprint, String userId, String appId) {
        // 生成令牌数据
        AccessToken accessToken = new AccessToken();
        accessToken.setId(code);
        accessToken.setUserId(userId);
        accessToken.setSecret(token.getSecretKey());

        AccessToken refreshToken = new AccessToken();
        refreshToken.setId(code);
        refreshToken.setUserId(userId);
        refreshToken.setSecret(token.getRefreshKey());

        long life = token.getLife() * 12;
        TokenPackage tokenPackage = new TokenPackage();
        tokenPackage.setAccessToken(Json.toBase64(accessToken));
        tokenPackage.setRefreshToken(Json.toBase64(refreshToken));
        tokenPackage.setExpire(token.getLife());
        tokenPackage.setFailure(life);

        // 缓存令牌数据
        String hashKey = tokenPackage.getAccessToken() + fingerprint;
        token.setHash(Util.md5(hashKey));
        Redis.set("Token:" + code, Json.toJson(token), life, TimeUnit.MILLISECONDS);

        // 更新用户缓存
        String key = "User:" + userId;
        Redis.set(key, appId, code);

        String json = Redis.get(key, "User");
        UserInfo info = Json.toBean(json, UserInfo.class);
        String imgUrl = info.getHeadImg();
        if (imgUrl == null || imgUrl.isEmpty()) {
            String defaultHead = Redis.get("Config:DefaultHead");
            info.setHeadImg(defaultHead);
        } else if (!imgUrl.contains("http://") && !imgUrl.contains("https://")) {
            String host = Redis.get("Config:FileHost");
            info.setHeadImg(host + imgUrl);
        }

        info.setTenantId(token.getTenantId());
        tokenPackage.setUserInfo(info);

        return tokenPackage;
    }

    /**
     * 获取缓存中的令牌数据
     *
     * @param tokenId 令牌ID
     * @return 缓存中的令牌
     */
    public Token getToken(String tokenId) {
        String key = "Token:" + tokenId;
        String json = Redis.get(key);
        if (json == null || json.isEmpty()) {
            return null;
        }

        return Json.toBean(json, Token.class);
    }

    /**
     * 使用户离线
     *
     * @param tokenId 令牌ID
     */
    public void deleteToken(String tokenId) {
        String key = "Token:" + tokenId;
        if (!Redis.hasKey(key)) {
            return;
        }

        Redis.deleteKey(key);
    }

    /**
     * 生成Code,缓存后返回
     *
     * @param userId  用户ID
     * @param key     密钥
     * @param seconds 缓存有效时间(秒)
     * @return Code
     */
    private String generateCode(String userId, String key, int seconds) {

        String code = Generator.uuid();
        String signature = Util.md5(key + code);

        // 用签名作为Key缓存Code
        Redis.set("Sign:" + signature, code, seconds, TimeUnit.SECONDS);

        // 用Code作为Key缓存用户ID
        Redis.set("Code:" + code, userId, seconds + 1, TimeUnit.SECONDS);

        return code;
    }

    /**
     * 生成短信验证码
     *
     * @param type    验证码类型(0:验证手机号;1:注册用户账号;2:重置密码;3:修改支付密码;4:登录验证码)
     * @param mobile  手机号
     * @param minutes 验证码有效时长(分钟)
     * @param length  验证码长度
     * @return 短信验证码
     */
    public String generateSmsCode(int type, String mobile, int minutes, int length) {
        String code = Generator.randomStr(length);
        logger.info("为手机号【" + mobile + "】生成了类型为" + type + "的验证码:" + code + ",有效时间:" + minutes + "分钟.");
        String key = "SMSCode:" + Util.md5(type + mobile + code);
        if (type == 4) {
            return code;
        }

        Redis.set(key, code, minutes, TimeUnit.MINUTES);
        return code;
    }

    /**
     * 同步发送短信
     *
     * @param templateId 短信模板ID
     * @param mobile     手机号
     * @param map        模板参数Map
     */
    public Reply sendMessageSyn(String templateId, String mobile, Map<String, Object> map) {
        Sms sms = new Sms();
        sms.setCode(templateId);
        sms.setParams(map);
        sms.setReceivers(Collections.singletonList(mobile));

        return null;
    }

    /**
     * 验证短信验证码
     *
     * @param type   验证码类型(0:验证手机号;1:注册用户账号;2:重置密码;3:修改支付密码;4:登录验证码)
     * @param mobile 手机号
     * @param code   验证码
     * @return 是否通过验证
     */
    public Boolean verifySmsCode(int type, String mobile, String code) {
        return verifySmsCode(type, mobile, code, false);
    }

    /**
     * 验证短信验证码
     *
     * @param type    验证码类型(0:验证手机号;1:注册用户账号;2:重置密码;3:修改支付密码;4:登录验证码)
     * @param mobile  手机号
     * @param code    验证码
     * @param isCheck 是否检验模式(true:检验模式,验证后验证码不失效;false:验证模式,验证后验证码失效)
     * @return 是否通过验证
     */
    public Boolean verifySmsCode(int type, String mobile, String code, Boolean isCheck) {
        String key = "SMSCode:" + Util.md5(type + mobile + code);
        Boolean isExisted = Redis.hasKey(key);
        if (!isExisted || isCheck) {
            return isExisted;
        }

        Redis.deleteKey(key);
        return true;
    }

    /**
     * 通过签名获取Code
     *
     * @param sign 签名
     * @return 签名对应的Code
     */
    public String getCode(String sign) {
        String key = "Sign:" + sign;
        String code = Redis.get(key);
        if (code == null || code.isEmpty()) {
            return null;
        }

        Redis.deleteKey(key);
        return code;
    }

    /**
     * 通过Code获取用户ID
     *
     * @param code Code
     * @return Code对应的用户ID
     */
    public String getId(String code) {
        String key = "Code:" + code;
        String id = Redis.get(key);
        if (id == null || id.isEmpty()) {
            return null;
        }

        Redis.deleteKey(key);
        return id;
    }

    /**
     * 用户是否存在
     *
     * @param user User数据
     * @return 用户是否存在
     */
    public Boolean userIsExisted(User user) {
        return mapper.getExistedUserCount(user.getAccount(), user.getMobile(), user.getEmail(), user.getUnionId()) > 0;
    }

    /**
     * 获取用户信息
     *
     * @param userId 用户ID
     * @param appId  微信AppID
     * @return 用户对象实体
     */
    public User getUser(String userId, String appId) {
        return mapper.getUserWithAppId(userId, appId);
    }

    /**
     * 用户是否失效状态
     *
     * @return 用户是否失效状态
     */
    public Boolean userIsInvalid(String userId) {
        String key = "User:" + userId;
        String value = Redis.get(key, "LastFailureTime");
        if (value == null || value.isEmpty()) {
            return false;
        }

        LocalDateTime lastFailureTime = LocalDateTime.parse(value);
        LocalDateTime resetTime = lastFailureTime.plusMinutes(10);
        LocalDateTime now = LocalDateTime.now();

        int failureCount = Integer.parseInt(Redis.get(key, "FailureCount"));
        if (failureCount > 0 && now.isAfter(resetTime)) {
            failureCount = 0;
        }

        return failureCount > 5 || Boolean.valueOf(Redis.get(key, "IsInvalid"));
    }

    /**
     * 根据授权码获取用户的微信OpenID
     *
     * @param code 授权码
     * @return 微信OpenID
     */
    public WeChatUser getWeChatInfo(String code, String weChatAppId) {
        Object secret = Redis.get(weChatAppId, "secret");

        return weChatHelper.getUserInfo(code, weChatAppId, secret.toString());
    }

    /**
     * 记录用户绑定的微信OpenID
     *
     * @param userId 用户ID
     * @param openId 微信OpenID
     * @param appId  微信AppID
     */
    public void bindOpenId(String userId, String openId, String appId) {
        Integer count = mapper.addUserOpenId(openId, userId, appId);
        if (count <= 0) {
            logger.error("绑定openId写入数据到数据库失败!");
        }
    }

    /**
     * 是否绑定了指定的应用
     *
     * @param tenantId 租户ID
     * @param appId    应用ID
     * @return 是否绑定了指定的应用
     */
    public Boolean containsApp(String tenantId, String appId) {
        return mapper.containsApp(tenantId, appId) > 0;
    }
}
