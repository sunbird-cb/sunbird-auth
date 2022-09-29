package org.sunbird.keycloak.login;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.messages.Messages;
import org.sunbird.keycloak.resetcredential.sms.KeycloakSmsAuthenticatorConstants;
import org.sunbird.keycloak.resetcredential.sms.KeycloakSmsAuthenticatorUtil;
import org.sunbird.keycloak.utils.Constants;
import org.sunbird.keycloak.utils.HttpClient;
import org.sunbird.keycloak.utils.SunbirdModelUtils;

public class PasswordAndOtpAuthenticator extends AbstractUsernameFormAuthenticator {

	Logger logger = Logger.getLogger(PasswordAndOtpAuthenticator.class);

	/**
	 * This page is called when UI calls
	 * "/realms/sunbird/protocol/openid-connect/auth" API.
	 */
	@Override
	public void authenticate(AuthenticationFlowContext context) {
		String flagPage = getValue(context, Constants.FLAG_PAGE);
		logger.info("OtpSmsFormAuthenticator::authenticate:: " + flagPage);
		goPage(context, Constants.LOGIN_PAGE);
	}

	@Override
	public boolean requiresUser() {
		return false;
	}

	@Override
	public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
		return true;
	}

	@Override
	public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
	}

	/**
	 * This method is called when UI calls
	 * "/realms/sunbird/login-actions/authenticate" API
	 */
	@Override
	public void action(AuthenticationFlowContext context) {
		logger.info("OtpSmsFormAuthenticator::action... ");
		MultivaluedMap<String, String> qParamMap = context.getHttpRequest().getUri().getQueryParameters(false);
		Iterator<Entry<String, List<String>>> itr = qParamMap.entrySet().iterator();
		while (itr.hasNext()) {
			Entry<String, List<String>> entry = itr.next();
			logger.info(String.format("		query: key: %s, value: %s", entry.getKey(), entry.getValue()));
		}

		String flagPage = getValue(context, Constants.FLAG_PAGE);
		logger.info("OtpSmsFormAuthenticator::action:: " + flagPage);
		switch (flagPage) {
		case Constants.FLAG_OTP_PAGE:
			authenticateOtp(context);
			break;
		case Constants.FLAG_OTP_RESEND_PAGE:
			resendOtp(context);
			break;
		case Constants.FLAG_LOGIN_PAGE:
			sendOtp(context, qParamMap.getFirst(Constants.REDIRECT_URI_KEY));
			break;
		case Constants.FLAG_LOGIN_WITH_PASS:
			if (!validateForm(context, context.getHttpRequest().getDecodedFormParameters())) {
				goErrorPage(context, "Invalid credentials!");
			} else {
				logger.info("Validation of username + password is successful... setting redirect_uri with "
						+ qParamMap.getFirst(Constants.REDIRECT_URI_KEY));
				context.getAuthenticationSession().setAuthNote(Details.REDIRECT_URI,
						qParamMap.getFirst(Constants.REDIRECT_URI_KEY));
				context.success();
			}
			break;
		default:
			authenticate(context);
			break;
		}
	}

	private String getValue(AuthenticationFlowContext context, String key) {
		MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
		String value = formData.getFirst(key);
		if (null == value) {
			value = "";
		}
		return value;
	}

	private void authenticateOtp(AuthenticationFlowContext context) {
		String sessionKey = context.getAuthenticationSession().getAuthNote(Constants.SESSION_OTP_CODE);
		if (sessionKey != null) {
			// Get OTP from User Input
			String otp = getValue(context, Constants.ATTR_OTP_USER);
			// Validate OTP
			if (otp != null) {
				if (otp.equals(sessionKey)) {
					MultivaluedMap<String, String> qParamMap = context.getHttpRequest().getUri()
							.getQueryParameters(false);
					logger.info("Validation of username + password is successful... "
							+ qParamMap.getFirst(Constants.REDIRECT_URI_KEY));
					context.getAuthenticationSession().removeAuthNote(Constants.SESSION_OTP_CODE);
//					context.getAuthenticationSession().setAuthNote(Details.REDIRECT_URI,
//							context.getAuthenticationSession().getAuthNote(Details.REDIRECT_URI));
					context.success();
				} else {
					goErrorPage(context, Constants.PAGE_INPUT_OTP, Constants.INVALID_OTP_ENTERED);
				}
			} else {
				goErrorPage(context, Constants.PAGE_INPUT_OTP, Constants.INVALID_OTP_ENTERED);
			}
		} else {
			goErrorPage(context, Constants.PAGE_INPUT_OTP, "Failed to get OTP Details from Session.");
		}
	}

	private void goErrorPage(AuthenticationFlowContext context, String message) {
		Response challenge = context.form().setError(message).createForm(Constants.LOGIN_PAGE);
		context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, challenge);
	}

	private void goErrorPage(AuthenticationFlowContext context, String page, String message) {
		Response challenge = context.form().setError(message).createForm(page);
		context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, challenge);
	}

	private void goPage(AuthenticationFlowContext context, String page) {
		context.challenge(context.form().createForm(page));
	}

	protected boolean validateForm(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
		return validateUserAndPassword(context, formData);
	}

	private String getEmailOrMobileNumber(AuthenticationFlowContext context) {
		MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
		String emailOrMobile = formData.getFirst(Constants.ATTR_USER_EMAIL_OR_PHONE);
		if (null == emailOrMobile) {
			return "";
		}
		return emailOrMobile;
	}

	private UserModel getUserByMobileNumber(AuthenticationFlowContext context, String mobilePhone) {
		UserModel user = null;
		try {
			user = SunbirdModelUtils.getUserByNameEmailOrPhone(context, mobilePhone);
		} catch (ModelDuplicateException mde) {
			ServicesLogger.LOGGER.modelDuplicateException(mde);
			// Could happen during federation import
			if (mde.getDuplicateFieldName() != null && mde.getDuplicateFieldName().equals(UserModel.EMAIL)) {
				setDuplicateUserChallenge(context, Errors.EMAIL_IN_USE, Messages.EMAIL_EXISTS,
						AuthenticationFlowError.USER_CONFLICT);
			} else if (mde.getDuplicateFieldName() != null && mde.getDuplicateFieldName().equals(UserModel.USERNAME)) {
				setDuplicateUserChallenge(context, Errors.USERNAME_IN_USE, Messages.USERNAME_EXISTS,
						AuthenticationFlowError.USER_CONFLICT);
			} else if (mde.getDuplicateFieldName() != null
					&& mde.getDuplicateFieldName().equals(KeycloakSmsAuthenticatorConstants.ATTR_MOBILE)) {
				setDuplicateUserChallenge(context, Constants.MULTIPLE_USER_ASSOCIATED_WITH_PHONE,
						Constants.MULTIPLE_USER_ASSOCIATED_WITH_PHONE, AuthenticationFlowError.USER_CONFLICT);
			}

			return null;
		}

		if (invalidUser(context, user)) {
			return null;
		}
		return user;
	}

	private void sendOtp(AuthenticationFlowContext context, String redirectUri) {
		String emailOrMobile = getEmailOrMobileNumber(context);
		UserModel user = getUserByMobileNumber(context, emailOrMobile);
		if (null == user) {
			goErrorPage(context, "Oops, Member not found.");
			return;
		}

		// Generate Random Digit
		String key = generateOTP(context);

		// Put the data into session, to be compared
		context.getAuthenticationSession().setAuthNote(Constants.ATTEMPTED_EMAIL_OR_MOBILE_NUMBER, emailOrMobile);
		context.getAuthenticationSession().setAuthNote(Constants.SESSION_OTP_CODE, key);
		context.getAuthenticationSession().setAuthNote(Details.REDIRECT_URI, redirectUri);

		// Send the key into the User Mobile Phone
		logger.error("Send OTP Code [" + key + "] to Phone Number [" + emailOrMobile + "]");
		if (sendOtpByEmailOrSms(context, emailOrMobile, key)) {
			context.setUser(user);
			goPage(context, Constants.PAGE_INPUT_OTP);
		} else {
			goErrorPage(context, "Failed to send out SMS. Please contact Administrator.");
		}
	}

	private void resendOtp(AuthenticationFlowContext context) {
		String mobileNumber = context.getAuthenticationSession()
				.getAuthNote(Constants.ATTEMPTED_EMAIL_OR_MOBILE_NUMBER);
		// Generate Random Digit
		String key = generateOTP(context);

		// Put the data into session, to be compared
		context.getAuthenticationSession().setAuthNote(Constants.SESSION_OTP_CODE, key);
		// Send the key into the User Mobile Phone
		logger.error("Send OTP Code [" + key + "] to Phone Number [" + mobileNumber + "]");
		if (sendOtpByEmailOrSms(context, mobileNumber, key)) {
			goPage(context, Constants.PAGE_INPUT_OTP);
		} else {
			goErrorPage(context, "Failed to send out SMS. Please contact Administrator.");
		}
	}

	private boolean sendOtpByEmailOrSms(AuthenticationFlowContext context, String mobileNumber, String otp) {
		boolean retValue = false;
		String userNameType = isEmailOrMobileNumber(mobileNumber);
		switch (userNameType) {
		case Constants.PHONE:
			AuthenticatorConfigModel configModel = context.getAuthenticatorConfig();
			String smsProvider = null;
			if (configModel.getConfig() != null) {
				smsProvider = configModel.getConfig().get(KeycloakSmsAuthenticatorConstants.CONF_PRP_SMS_PROVIDER);
			}
			if (Constants.MSG91_PROVIDER.equalsIgnoreCase(smsProvider)) {
				retValue = KeycloakSmsAuthenticatorUtil.send(mobileNumber, otp);
			} else if (Constants.Free2SMS_PROVIDER.equalsIgnoreCase(smsProvider)) {
				retValue = sendSmsViaFast2Sms(mobileNumber, otp);
			}
			break;
		case Constants.EMAIL:
			retValue = sendEmailViaSunbird(context, mobileNumber, otp);
			break;
		}
		return retValue;
	}

	private boolean sendSmsViaFast2Sms(String mobileNumber, String otp) {
		List<String> acceptedNumbers = new ArrayList<String>();
		if (StringUtils.isNotBlank(System.getenv(Constants.SMS_OTP_NUMBERS))) {
			acceptedNumbers = Arrays.asList(System.getenv(Constants.SMS_OTP_NUMBERS).split(",", -1));
		}
		if (!acceptedNumbers.contains(mobileNumber)) {
			return false;
		}

		try {
			// Construct data
			StringBuilder strUrl = new StringBuilder(System.getenv(Constants.FAST2SMS_API_URL));
			strUrl.append("?authorization=").append(System.getenv(Constants.FAST2SMS_API_KEY));
			strUrl.append("&route=v3");
			strUrl.append("&sender_id=FTWSMS");
			strUrl.append("&message=Your%20OTP%20login%20into%20iGOT%20System%20is%20:%20" + otp);
			strUrl.append("&language=english&flash=0");
			strUrl.append("&numbers=").append(mobileNumber);

			// Send SMS
			HttpURLConnection conn = (HttpURLConnection) new URL(strUrl.toString()).openConnection();
			conn.setDoOutput(true);
			conn.setRequestMethod("GET");
			final BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			final StringBuffer stringBuffer = new StringBuffer();
			String line;
			while ((line = rd.readLine()) != null) {
				stringBuffer.append(line);
			}
			rd.close();

			logger.info(stringBuffer.toString());
			return true;
		} catch (Exception e) {
			System.out.println("Error SMS " + e);
			logger.error(e);
		}
		return false;
	}

	private String generateOTP(AuthenticationFlowContext context) {
		// The mobile number is configured --> send an SMS
		long nrOfDigits = KeycloakSmsAuthenticatorUtil.getConfigLong(context.getAuthenticatorConfig(),
				KeycloakSmsAuthenticatorConstants.CONF_PRP_SMS_CODE_LENGTH, 8L);
		logger.debug("Using nrOfDigits " + nrOfDigits);

		logger.debug("KeycloakSmsAuthenticator@sendSMS");

		// Get TTL from config. Default 5 minutes in seconds
		long ttl = KeycloakSmsAuthenticatorUtil.getConfigLong(context.getAuthenticatorConfig(),
				KeycloakSmsAuthenticatorConstants.CONF_PRP_SMS_CODE_TTL, 5 * 60L);

		logger.debug("Using ttl " + ttl + " (s)");
		String code = KeycloakSmsAuthenticatorUtil.getSmsCode(nrOfDigits);

		context.getAuthenticationSession().setAuthNote(Constants.SESSION_OTP_CODE, code);
		context.getAuthenticationSession().setAuthNote(Constants.SESSION_OTP_EXPIRE_TIME,
				String.valueOf(new Date().getTime() + (ttl * 1000)));

		return code;
	}

	private boolean sendEmailViaSunbird(AuthenticationFlowContext context, String userEmail, String smsCode) {
		logger.debug("KeycloakSmsAuthenticator@sendEmailViaSunbird - Sending Email via Sunbird API");

		List<String> emails = new ArrayList<>(Arrays.asList(userEmail));
		Map<String, Object> otpResponse = new HashMap<String, Object>();

		otpResponse.put(Constants.RECIPIENT_EMAILS, emails);
		otpResponse.put(Constants.SUBJECT, Constants.MAIL_SUBJECT);
		otpResponse.put(Constants.REALM_NAME, context.getRealm().getDisplayName());
		otpResponse.put(Constants.EMAIL_TEMPLATE_TYPE, Constants.FORGOT_PASSWORD_EMAIL_TEMPLATE);
		otpResponse.put(Constants.BODY, Constants.BODY);
		otpResponse.put(Constants.OTP, smsCode);

		Map<String, Object> request = new HashMap<>();
		request.put(Constants.REQUEST, otpResponse);

		HttpResponse response = HttpClient.post(request,
				(System.getenv(Constants.SUNBIRD_LMS_BASE_URL) + Constants.SEND_NOTIFICATION_URI),
				System.getenv(Constants.SUNBIRD_LMS_AUTHORIZATION));

		int statusCode = response.getStatusLine().getStatusCode();
		if (statusCode == 200) {
			return true;
		}
		return false;
	}

	private String isEmailOrMobileNumber(String emailOrMobile) {
		String numberRegex = "\\d+";
		String emailRegex = "^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@"
				+ "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";
		if (emailOrMobile.matches(numberRegex) && 10 == emailOrMobile.length()) {
			return Constants.PHONE;
		} else if (emailOrMobile.matches(emailRegex)) {
			return Constants.EMAIL;
		}
		return StringUtils.EMPTY;
	}
}
