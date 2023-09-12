package co.tunan.template.application.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWT;
import co.tunan.template.application.model.ro.AdminLoginRo;
import co.tunan.template.application.model.ro.EditPwdRo;
import co.tunan.template.application.model.vo.admin.AdminAuthorityVo;
import co.tunan.template.application.model.vo.admin.AdminLoginDetailVo;
import co.tunan.template.application.model.vo.admin.AdminLoginVo;
import co.tunan.template.common.helper.BeanFiller;
import co.tunan.template.common.helper.exception.InvokeException;
import co.tunan.template.common.helper.exception.NoAuthException;
import co.tunan.template.common.helper.exception.ValidateException;
import co.tunan.template.common.util.ImageVerCodeUtil;
import co.tunan.template.common.util.JsonUtil;
import co.tunan.template.common.util.MD5Util;
import co.tunan.template.repo.entity.Authorization;
import co.tunan.template.repo.mapper.AdminMapper;
import co.tunan.template.repo.mapper.AuthorizationMapper;
import co.tunan.template.services.ms.common.RedisCacheService;
import co.tunan.template.services.ms.common.SessionHolder;
import co.tunan.template.services.ms.common.configuration.AppConfig;
import co.tunan.template.services.ms.common.context.AdminContext;
import co.tunan.template.services.ms.common.model.enumeration.AuthorizationType;
import co.tunan.template.services.ms.common.model.enumeration.Const;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.inject.Inject;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @title: AuthService
 * @author: trifolium
 * @date: 2023/1/9
 * @modified :
 */
@Service
@Transactional
public class AuthService {

    @Inject
    private RedissonClient redissonClient;

    @Inject
    private RedisCacheService redisCacheService;

    @Inject
    private AdminContext adminContext;

    @Inject
    private AdminMapper adminMapper;

    @Inject
    private AuthorizationMapper authorizationMapper;

    @Inject
    private AppConfig appConfig;

    public AdminLoginVo login(AdminLoginRo ro) {
        var rateLimiter = redissonClient.getRateLimiter("rate_limiter:user_login:" + ro.getUserName());
        // 通过用户名限制刷验证码
        rateLimiter.trySetRate(RateType.OVERALL, 20, 10, RateIntervalUnit.MINUTES);
        if (rateLimiter.tryAcquire()) {
            checkCaptcha(ro.getCaptcha());
            var admins = adminMapper.findByUserName(ro.getUserName());
            var admin = CollUtil.isEmpty(admins) ? null : admins.get(0);
            if (admin == null || !StrUtil.equalsIgnoreCase(MD5Util.getMD5String(ro.getPassword()),
                    admin.getPassword())) {
                throw new ValidateException("账号或密码错误");
            }
            if (BooleanUtil.isTrue(admin.getIsBanned())) {
                throw new NoAuthException("账号被禁用，无法登录");
            }

            // 组装前台需要的数据
            var loginVo = new AdminLoginVo();
            var adminVo = BeanUtil.toBean(admin, AdminLoginDetailVo.class);

            List<Authorization> adminAuthorizations = new ArrayList<>();
            if (!admin.getIsSuper()) {
                adminVo.setRoleCodes(JsonUtil.jsonToList(admin.getRoleCodes(), String.class));
                if (CollUtil.isNotEmpty(adminVo.getRoleCodes())) {
                    for (String roleCode : adminVo.getRoleCodes()) {
                        List<Authorization> roleAuth = authorizationMapper.findAuthorizationByRoleCode(roleCode);
                        adminAuthorizations.addAll(roleAuth);
                    }

                    // 设置接口权限
                    loginVo.setAuthorities(BeanFiller.target(AdminAuthorityVo.class).acceptList(adminAuthorizations
                            .stream().filter(
                                    a -> Objects.equals(AuthorizationType.INTERFACE.getCode(),
                                            String.valueOf(a.getType()))).collect(Collectors.toList())));
                } else {
                    throw new InvokeException("角色禁用");
                }
            }
            loginVo.setAdminVo(adminVo);
            long expires = System.currentTimeMillis() + (appConfig.getTimeout() * 1000);
            // 生成jwt
            loginVo.setToken(JWT.create()
                    .setSubject(admin.getId() + "")
                    .setExpiresAt(new Date(expires))
                    .setIssuedAt(new Date())
                    .setJWTId(StrUtil.uuid().replace("-", ""))
                    .setPayload("name", admin.getName())
                    .setKey(Const.JWT_PRIVATE_KEY.getBytes()).sign());

            loginVo.setTokenType("Bearer");
            loginVo.setExpires(expires);

            // 缓存用户与权限数据
            adminContext.login(admin, adminAuthorizations, appConfig.getTimeout());
            return loginVo;

        } else {

            throw new InvokeException("连续输入错误次数太多,请10分钟之后再试!");
        }
    }

    /**
     * 输出验证码
     */
    public void captchaImage(HttpServletResponse response) throws IOException {
        var session = SessionHolder.getSession();
        if (session == null) {
            throw new RuntimeException("请求错误");
        }
        var sessionId = session.getId();
        var ip = SessionHolder.getRemoteIp();
        // 通过ip限制刷验证码
        var rateLimiter = redissonClient.getRateLimiter("rate_limiter:get_captcha_image:" + ip);
        rateLimiter.trySetRate(RateType.OVERALL, 60, 3, RateIntervalUnit.MINUTES);
        if (rateLimiter.tryAcquire()) {

            var cookie = new Cookie(Const.CAPTCHA_SESSION_NAME, sessionId);
            cookie.setPath("/");
            response.addCookie(cookie);
            response.addHeader(Const.CAPTCHA_SESSION_NAME, sessionId);
            response.addHeader("Access-Control-Expose-Headers", "*");
            response.setContentType(MediaType.IMAGE_JPEG_VALUE);
            session.setAttribute(Const.CAPTCHA_SESSION_NAME, sessionId);
            String captcha = new ImageVerCodeUtil().outputVerifyImage(200, 50, response.getOutputStream(), 4);
            // 图片验证码有效期10分钟
            redisCacheService.set(Const.REDIS_USER_LOGIN_CAPTCHA_PREFIX + sessionId, captcha, 60 * 10L);
        } else {

            throw new InvokeException("请求次数过多，3分钟后请再次尝试");
        }
    }

    /**
     * 校验登录验证码
     */
    private void checkCaptcha(String captcha) {
        // 验证图片验证码
        var request = SessionHolder.getRequest();
        if (request == null) {
            throw new RuntimeException("请求错误");
        }
        Cookie[] cookies = request.getCookies();
        String sessionId = null;
        if (cookies != null) {
            sessionId = Arrays.stream(cookies)
                    .filter(c -> c.getName().equals(Const.CAPTCHA_SESSION_NAME)).map(
                            Cookie::getValue).findFirst().orElse(null);
        }
        if (StrUtil.isEmpty(sessionId)) {
            sessionId = request.getHeader(Const.CAPTCHA_SESSION_NAME);
            if (StrUtil.isEmpty(sessionId)) {
                var session = SessionHolder.getSession();
                sessionId = session == null ? null : ((String) session.getAttribute(Const.CAPTCHA_SESSION_NAME));
            }
        }

        String cacheCaptcha = redisCacheService.getString(Const.REDIS_USER_LOGIN_CAPTCHA_PREFIX + sessionId);

        if (StrUtil.isEmpty(captcha) || !captcha.equalsIgnoreCase(cacheCaptcha)) {

            redisCacheService.delete(Const.REDIS_USER_LOGIN_CAPTCHA_PREFIX + sessionId);
            throw new ValidateException("验证码错误");
        }
    }

    /**
     * 修改密码
     */
    public void editPwd(EditPwdRo ro) {
        var admin = adminContext.getAdmin();

        var adminDb = adminMapper.selectByPrimaryKey(admin.getId());

        if (adminDb == null || !StrUtil.equalsIgnoreCase(MD5Util.getMD5String(ro.getOldPwd()), adminDb.getPassword())) {
            throw new InvokeException("原密码错误");
        }

        adminDb.setPassword(MD5Util.getMD5String(ro.getNewPwd()));

        adminMapper.updateByPrimaryKey(adminDb);
    }

    /**
     * 管理员登出
     */
    public void logout() {

        adminContext.logout();
    }

}
