package org.sunbird.keycloak.login;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelDuplicateException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.messages.Messages;
import org.sunbird.keycloak.resetcredential.sms.KeycloakSmsAuthenticatorConstants;
import org.sunbird.keycloak.utils.Constants;
import org.sunbird.keycloak.utils.SunbirdModelUtils;
import org.sunbird.utils.ProjectUtils;

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
					logger.info("Validation of username + password is successful... setting redirect_uri with "
							+ qParamMap.getFirst(Constants.REDIRECT_URI_KEY));
					context.getAuthenticationSession().setAuthNote(Details.REDIRECT_URI,
							context.getAuthenticationSession().getAuthNote(Details.REDIRECT_URI));
					context.success();
				} else {
					goErrorPage(context, "Invalid OTP Code.");
				}
			} else {
				goPage(context, Constants.PAGE_INPUT_OTP);
			}
		} else {
			goErrorPage(context, "Failed to get OTP Details from Session.");
		}
	}

	private void goErrorPage(AuthenticationFlowContext context, String message) {
		Response challenge = context.form().setError(message).createForm(Constants.LOGIN_PAGE);
		context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR, challenge);
	}

	private void goPage(AuthenticationFlowContext context, String page) {
		context.challenge(context.form().createForm(page));
	}

	protected boolean validateForm(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
		return validateUserAndPassword(context, formData);
	}

	private String getMobileNumber(AuthenticationFlowContext context) {
		MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
		String number = formData.getFirst(Constants.ATTR_PHONE_NUMBER);
		if (null == number) {
			return "";
		}
		return number;
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
		String mobile = getMobileNumber(context);
		UserModel user = getUserByMobileNumber(context, mobile);
		if (null == user) {
			goErrorPage(context, "Oops, Member not found.");
			return;
		}

		// Generate Random Digit
		String key = ProjectUtils.nextString(6, Constants.NUMERIC);

		// Put the data into session, to be compared
		context.getAuthenticationSession().setAuthNote(Constants.SESSION_OTP_CODE, key);
		context.getAuthenticationSession().setAuthNote(Details.REDIRECT_URI, redirectUri);

		// Send the key into the User Mobile Phone
		logger.error("Send OTP Code [" + key + "] to Phone Number [" + mobile + "]");
		sendSms(mobile, key);
		context.setUser(user);
		goPage(context, Constants.PAGE_INPUT_OTP);
	}

	private void sendSms(String mobileNumber, String otp) {
		List<String> acceptedNumbers = new ArrayList<String>();
		if (StringUtils.isNotBlank(System.getenv(Constants.SMS_OTP_NUMBERS))) {
			acceptedNumbers = Arrays.asList(System.getenv(Constants.SMS_OTP_NUMBERS).split(",", -1));
		}
		if (!acceptedNumbers.contains(mobileNumber)) {
			return;
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
		} catch (Exception e) {
			System.out.println("Error SMS " + e);
			logger.error(e);
		}
	}
}
