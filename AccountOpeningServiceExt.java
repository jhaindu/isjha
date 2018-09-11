package com.appzillon.db.acntop.accountOpening;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.ResourceBundle;

import javax.imageio.ImageIO;
import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.apache.camel.spring.SpringCamelContext;

import com.appzillon.db.acntop.accountOpening.model.TbDbtpAccountInformation;
import com.appzillon.db.acntop.accountOpening.model.TbDbtpCustInformation;
import com.appzillon.db.acntop.accountOpening.model.TbDbtpIinCode;
import com.appzillon.db.common.Constants;
import com.appzillon.db.common.I18N;
import com.appzillon.db.common.TbAgmiAgentMaster;
import com.appzillon.db.common.Utils;
import com.appzillon.db.common.tbAgmiAppParams;
import com.appzillon.db.impl.DMSExt;
import com.appzillon.db.limits.CashLimitLogicService;
import com.appzillon.service.sms.SMSService;
import com.iexceed.appzillon.json.JSONArray;
import com.iexceed.appzillon.json.JSONException;
import com.iexceed.appzillon.json.JSONObject;
import com.iexceed.appzillon.logging.Logger;
import com.iexceed.appzillon.logging.LoggerFactory;
import com.iexceed.appzillon.message.Message;
import com.iexceed.appzillon.utils.ServerConstants;

import sun.misc.BASE64Decoder;

public class AccountOpeningServiceExt {

	private static final Logger LOG = LoggerFactory.getLoggerFactory()
			.getFrameWorksLogger(ServerConstants.LOGGER_FRAMEWORKS, AccountOpeningServiceExt.class.toString());

	public Boolean processCustom(Message pMessage, SpringCamelContext pContext) {
		Boolean lReturnStatus = false;
		LOG.info(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "Entering AccountOpeningExt.processCustom -->1");
		lReturnStatus = callExternalService(pMessage, pContext);
		LOG.info(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "Exiting  AccountOpeningExt.processCustom -->2");

		return lReturnStatus;
	}

	private Boolean callExternalService(Message pMessage, SpringCamelContext pContext) {
		LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " AccountOpeningExt:Into callExternalService method");
		JSONObject lNodeReq = new JSONObject();
		JSONObject lActReq = pMessage.getRequestObject().getRequestJson();
		lNodeReq = pMessage.getRequestObject().getRequestJson().getJSONObject("AccOpen");
		JSONObject lcustinfo = lNodeReq.getJSONObject("Custinfo");

		String lNewdepamt = lNodeReq.getJSONObject("AccountInformation").getString("InitialDepAmount");
		CashLimitLogicService limitCheck = new CashLimitLogicService();

		LOG.debug("InitialDepAmount is..." + lNewdepamt);

		String limage = "";
		if (lcustinfo.has("image")) {
			limage = lcustinfo.getString("image");
		}
		String lname = "";
		if (lcustinfo.has("Name")) {
			lname = lcustinfo.getString("Name");
		}
		String lpan = "";
		if (lcustinfo.has("PanNumber")) {
			lpan = lcustinfo.getString("PanNumber");
		}
		String lmobile = "";
		if (lcustinfo.has("MobileNumber")) {
			lmobile = lcustinfo.getString("MobileNumber");
		}

		String txnType = lNodeReq.getString("txnType");

		Boolean lStatus = true;
		Boolean lLimitStatus = true;
		Utils lUtils = new Utils();
		JSONObject lServiceRequest = new JSONObject();
		String lAppParamCode = "BANK_ID";

		String pcutomerId = getrandomNumber();
		String pTxnType = "AccountOpening";

		String lrefno = insertMasterTable(pMessage, pcutomerId, new BigDecimal(00), pTxnType);
		LOG.debug("APPID is :: " + pMessage.getHeader().getAppId().equals("MICR01"));
		if (pMessage.getHeader().getAppId().equals("MICR01")) {
			LOG.debug("Limit Check Starts...");
			prepareLimitCheckRequest(pMessage, lrefno, lNewdepamt);
			lLimitStatus = limitCheck.checkLimit(pMessage);
			LOG.debug("Limit Check Ends.." + lLimitStatus);
			if (!lLimitStatus) {
				LOG.debug("Limit Failed....");
				JSONObject lresponse = buildResponse("", "",
						pMessage.getResponseObject().getResponseJson().getString("errMsg"), "disp", "", "", "",
						"failure");
				pMessage.getResponseObject().setResponseJson(lresponse);
				return false;
			}
			pMessage.getRequestObject().setRequestJson(lActReq);
		}
		// ========= Fetching BankId ================//
		String lBankId = getBankId(pMessage, lAppParamCode);
		LOG.info(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " AccountOpeningExt:" + lBankId);
		lServiceRequest = createServiceReq(pMessage, pContext, lBankId, lrefno);
		LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "lServiceRequest is " + lServiceRequest);
		pMessage.getRequestObject().setRequestJson(lServiceRequest);
		pMessage.getHeader().setInterfaceId("acncor__AccountOpeningCore");
		lStatus = lUtils.handleRequest(pMessage);
		JSONObject finalresp = new JSONObject();
		JSONObject lresObj = pMessage.getResponseObject().getResponseJson();
		if (lStatus) {
			LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "lStatus is " + lStatus);
			LOG.debug("Response from AccountopeningService......" + lresObj);
			JSONObject objHeader = lresObj.optJSONObject("FIXML").getJSONObject("Header")
					.getJSONObject("ResponseHeader");
			LOG.debug("Header from the request is: " + objHeader);

			String laccountNumber = "";
			String lcifid = "";
			if (objHeader.getJSONObject("HostTransaction").getString("Status").equals("SUCCESS")) {
				if(lresObj.getJSONObject("FIXML").getJSONObject("Body").getJSONObject("executeFinacleScriptResponse").getJSONObject("executeFinacleScript_CustomData").has("Status")){
					LOG.debug("Response has status as FAILURE in body");
					String erorrMsg = lresObj.getJSONObject("FIXML").getJSONObject("Body").getJSONObject("executeFinacleScriptResponse")
							.getJSONObject("executeFinacleScript_CustomData").getString("ErrorDesc");	
					
					LOG.debug("Error Message is"+erorrMsg);
					
					finalresp = buildResponse("", "", erorrMsg, "disp", erorrMsg, "", "", "failure");
					LOG.debug("finalresp: " + finalresp);
				}else{
				LOG.debug("HostTransaction has status SUCCESS");
				String messagedate = objHeader.getJSONObject("ResponseMessageInfo").getString("MessageDateTime");
				LOG.debug("Messagedate from the response is" + messagedate);
				JSONObject objBody = lresObj.optJSONObject("FIXML").getJSONObject("Body")
						.getJSONObject("executeFinacleScriptResponse");
				LOG.debug("Body from the request is: " + objBody);
				convertToString(objBody);
				lcifid = objBody.getJSONObject("executeFinacleScript_CustomData").getString("CIF_ID");
				laccountNumber = objBody.getJSONObject("executeFinacleScript_CustomData")
						.getString("SBA_ACCOUNT_NUMBER");
				String ltranID = objBody.getJSONObject("executeFinacleScript_CustomData").getString("TRAN_ID");
				LOG.debug("lcifid: " + lcifid);
				LOG.debug("laccountNumber: " + laccountNumber);
				LOG.debug("ltranID: " + ltranID);
				finalresp = buildResponse(messagedate, ltranID, "", "app", "", laccountNumber, lcifid, "success");
				LOG.debug("finalresp: " + finalresp);
				LOG.debug("Going to Send SMS to User" + finalresp);
				String laccmessage = "";
				if(pMessage.getHeader().getAppId().equals("MICR01")){
				 laccmessage=lUtils.getAlertMessage("ACCOPEN_MSG_TEXT", "EN");
				}else{
					laccmessage=lUtils.getAlertMessage("ACCOPEN_MSG_MB", "EN");
				}
				LOG.debug("Message to be sent.." + laccmessage);
				try {
					String[] lAccName = lname.split("\\s+");
					if(lAccName[0].length()>25){
						lAccName[0] = lAccName[0].substring(0,25);
					}
					laccmessage=laccmessage.replaceAll("#NAME", lAccName[0]);	
					laccmessage=laccmessage.replaceAll("#CIFID", lcifid);	
					laccmessage=laccmessage.replaceAll("#ACCNUMBER", laccountNumber);	
					LOG.debug("Message to be sent to user ::" + laccmessage);
					/*String lmessage = "Dear " + lname
							+ ", congratulations on creating a new account in IPPB. Your CustomerID is " + lcifid
							+ " and Account Number is " + laccountNumber;*/
					SMSService smsService = new SMSService();
		  			String smsResp = smsService.callSMSType(pMessage, pContext, lmobile, laccmessage, "TransMsg");
					LOG.debug("SMSResp : " + smsResp);
					
					//Updating limit check
					LOG.debug("Updating limit check ");
					prepareLimitCheckAfterAccOpen(pMessage, lrefno, lNewdepamt, laccountNumber);
					limitCheck.persistLimit(pMessage);
					LOG.debug("Updating limit check done");
				} catch (Exception ex) {
					LOG.error("Exception Occured ", ex);
					/*LOG.debug("[FrameworkServices] => Exception  ...." + ex.getMessage());
					LOG.debug("[FrameworkServices] => Exception  ...." + ex.getLocalizedMessage());*/
				}
				// Updating Master Table with AccountNumber
				if (lNodeReq.has("srcType")) {
					LOG.debug("Request has new user....");
					String lPermanent_AddrLine1 = "";
					if (lcustinfo.has("HouseLocation")) {
						lPermanent_AddrLine1 = lcustinfo.getString("HouseLocation");
					}
					String lPermanent_AddrLine2 = "";
					if (lcustinfo.has("HouseStreet")) {
						lPermanent_AddrLine2 = lcustinfo.getString("HouseStreet");
					}
					String lstate = "";
					if (lcustinfo.has("HouseState")) {
						lstate = lcustinfo.getString("HouseState");
					}
					String lcity = "";
					if (lcustinfo.has("HouseCity")) {
						lcity = lcustinfo.getString("HouseCity");
					}
					String lpincodes = "";
					BigDecimal lpincode = null;
					if (lcustinfo.has("HousePincode")) {
						lpincodes = lcustinfo.getString("HousePincode");
						if (!lpincodes.equals("")) {
							lpincode = new BigDecimal(lpincodes);
						}
					}
					EntityManager gEntityManager = null;
					try {
						gEntityManager = lUtils.getEntityManager();
						gEntityManager.getTransaction().begin();
						LOG.debug("Inside newUser() -->2");
						String loggedInuserID = pMessage.getHeader().getUserId();
						LOG.debug("loggedInuserID ::" + loggedInuserID);
						TbAgmiAgentMaster ltTbAgmiAgentMaster = new TbAgmiAgentMaster();
						ltTbAgmiAgentMaster = gEntityManager.find(TbAgmiAgentMaster.class, loggedInuserID);
						ltTbAgmiAgentMaster.setAgentAccount(laccountNumber);
						ltTbAgmiAgentMaster.setPermanentAddressLine1(lPermanent_AddrLine1);
						ltTbAgmiAgentMaster.setPermanentAddressLine2(lPermanent_AddrLine2);
						ltTbAgmiAgentMaster.setPermanentCity(lcity);
						ltTbAgmiAgentMaster.setPermanentState(lstate);
						ltTbAgmiAgentMaster.setPermanentPincode(lpincode);
						gEntityManager.merge(ltTbAgmiAgentMaster);
						LOG.debug("Inside newUser() -->3");
						gEntityManager.getTransaction().commit();
						LOG.debug("Inside newUser() -->4");
					} catch (Exception ex) {
						LOG.error("Exception Occured ", ex);
						/*LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getMessage());
						LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getLocalizedMessage());
						LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getStackTrace());*/
					} finally {
						if (gEntityManager != null) {
							gEntityManager.close();
							LOG.debug("Closed Entitymanager");
						}
					}
				}

				// updating TB_DBTP_ACCOUNT_INFORMATION with accountNumber
				EntityManager gEntityManager = null;
				try {
					LOG.debug("updating  TB_DBTP_ACCOUNT_INFORMATION with accountNumber -->1");
					gEntityManager = lUtils.getEntityManager();
					gEntityManager.getTransaction().begin();
					TbDbtpAccountInformation ltTbDbtpAccountInformation = new TbDbtpAccountInformation();
					ltTbDbtpAccountInformation = gEntityManager.find(TbDbtpAccountInformation.class, lrefno);
					LOG.debug("updating  TB_DBTP_ACCOUNT_INFORMATION with accountNumber -->2");
					LOG.debug("updating  TB_DBTP_ACCOUNT_INFORMATION with accountNumber" + laccountNumber);
					ltTbDbtpAccountInformation.setExternalAccountNo(laccountNumber);
					gEntityManager.merge(ltTbDbtpAccountInformation);
					gEntityManager.getTransaction().commit();
					LOG.debug("updating  TB_DBTP_ACCOUNT_INFORMATION with accountNumber -->3");
				} catch (Exception ex) {
					LOG.error("Exception Occured ", ex);
					/*LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getMessage());
					LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getLocalizedMessage());
					LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getStackTrace());*/
				} finally {
					if (gEntityManager != null) {
						gEntityManager.close();
						LOG.debug("Closed Entitymanager");
					}
				}

				try {
					LOG.debug("updating  TB_DBTP_CUST_INFORMATION with cifID -->1");
					gEntityManager = lUtils.getEntityManager();
					gEntityManager.getTransaction().begin();
					TbDbtpCustInformation lTbDbtpCustInformation = new TbDbtpCustInformation();
					lTbDbtpCustInformation = gEntityManager.find(TbDbtpCustInformation.class, lrefno);
					LOG.debug("updating  TB_DBTP_CUST_INFORMATION with accountNumber -->2");
					lTbDbtpCustInformation.setCustUid(lcifid);
					gEntityManager.merge(lTbDbtpCustInformation);
					gEntityManager.getTransaction().commit();
					LOG.debug("updating  TB_DBTP_CUST_INFORMATION with cifID -->3");
				} catch (Exception ex) {
					LOG.error("Exception Occured ", ex);
					/*LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getMessage());
					LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getLocalizedMessage());
					LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getStackTrace());*/
				} finally {
					if (gEntityManager != null) {
						gEntityManager.close();
						LOG.debug("Closed Entitymanager");
					}
				}

				try {
					//	ThreadContext.put("logRouter", appId + "/"+ userId);
						LOG.debug("Going to upload the image by calling DMS service");
						JSONObject dmsRequest = new JSONObject();
						JSONObject ldmsRequest = new JSONObject();
						Date lcurrDate = new Date();

						String fileName = lname+lcurrDate+".jpg";
						LOG.debug("File Name is :"+fileName);
						String mimeType = "image/jpeg";
						String content = limage;
						String laction = "Add";
						LOG.debug("Inside UploadFileToDMS()---------->2");

						String pattern = "ddMMyyHHmmSS";
						SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern);
						String date = simpleDateFormat.format(new Date());
						
						String prf=laccountNumber+date+".jpg";
						LOG.debug("Image name"+prf);
						ldmsRequest.put("fileName",prf);
						ldmsRequest.put("custId", lcifid);
						ldmsRequest.put("action", laction);
						ldmsRequest.put("mimeType", mimeType);
						ldmsRequest.put("content",content);
						ldmsRequest.put("purpose","Document ESign");
						ldmsRequest.put("accNo",laccountNumber);
						ldmsRequest.put("KycdocType","Aadhar Document");
						
						dmsRequest.put("DMSRequest", ldmsRequest);
						LOG.debug("DMS Request Formed is :" + dmsRequest);
						LOG.debug("Inside UploadFileToDMS()---------->3");

						LOG.debug("Request for DMS : " + dmsRequest);
						LOG.debug("Going to call DMS service..");
						pMessage.getRequestObject().setRequestJson(dmsRequest);
						LOG.debug("gRefnum is : " + lrefno);
						DMSExt dmsext = new DMSExt();
						boolean status = dmsext.ProcessCustom(lrefno, pMessage, pContext);
						LOG.debug("Inside UploadFileToDMS()---------->4");
						LOG.debug("Response form DMS  service: " + status);
						if (status) {
							LOG.debug("Response from DMS server is success" + status);
						} else {
							LOG.debug("Response failed" + status);

							LOG.debug("Uploading file to server" + status);
							String base64String = limage;

							// decode base64 encoded image
							BASE64Decoder decoder = new BASE64Decoder();
							byte[] decodedBytes = decoder.decodeBuffer(base64String);
							LOG.debug("Decoded upload data : " + decodedBytes.length);

							String limagePath=lUtils.getAlertMessage("ACCOPEN_BASE64","EN");
							LOG.debug("limagePath :" + limagePath);

						//	String uploadFile = "/appzilon/IPPB/WARS/6-April-2018/"+lname+".png";
							String uploadFile = limagePath+lname+".png";
							LOG.debug("File save path : " + uploadFile);

							// buffered image from the decoded bytes
							BufferedImage image = ImageIO.read(new ByteArrayInputStream(decodedBytes));
							if (image == null) {
								LOG.debug("Buffered Image is null");
							}
							File f = new File(uploadFile);

							// write the image
							ImageIO.write(image, "png", f);
							LOG.debug("Completed writing the image..");
							LOG.debug("Exiting UploadFileToDMS()---------->5");
						}
					} catch (Exception ex) {
						LOG.error("Exception Occured ", ex);
					}
				}
				
			} else {
				String erorrMsg = lresObj.getJSONObject("FIXML").getJSONObject("Body").getJSONObject("Error")
						.getJSONObject("FISystemException").getJSONObject("ErrorDetail").getString("ErrorDesc");
				LOG.debug("ERROR Message :" + erorrMsg);
				LOG.debug("HostTransaction has status FAILURE");
				finalresp = buildResponse("", "", "", "disp", erorrMsg, "", "", "failure");
				LOG.debug("finalresp: " + finalresp);
			}
			
			
		} else {
			String erorrMsg = lresObj.getJSONObject("FIXML").getJSONObject("Body").getJSONObject("Error")
					.getJSONObject("FISystemException").getJSONObject("ErrorDetail").getString("ErrorDesc");
			LOG.debug("ERROR Message :" + erorrMsg);
			LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "lStatus is " + lStatus);
			LOG.debug("Response from AccountopeningService......" + lresObj);
			finalresp = buildResponse("", "", "", "disp", erorrMsg, "", "", "failure");
			LOG.debug("finalresp: " + finalresp);

		}
		pMessage.getResponseObject().setResponseJson(finalresp);
		return lStatus;
	}

	private void prepareLimitCheckRequest(Message pMessage, String txnRefNo, String lAmount) {
		JSONObject actualReq = new JSONObject();
		JSONObject txnHolderNode = new JSONObject();
		JSONObject txnHolder = new JSONObject();

		txnHolder.put("txnType", "");
		txnHolder.put("customerId", pMessage.getHeader().getUserId());
		txnHolder.put("txnRefNo", txnRefNo);
		txnHolder.put("fromAccNo", "");
		txnHolder.put("toAccNo", "");
		txnHolder.put("txnAmt", lAmount);
		txnHolder.put("benId", "");

		txnHolderNode.put("RequestJson", txnHolder);
		pMessage.getRequestObject().setRequestJson(txnHolderNode);

	}
	
	
	private void prepareLimitCheckAfterAccOpen(Message pMessage, String txnRefNo, String lAmount,String lAccNumber) {
		JSONObject txnHolderNode = new JSONObject();
		JSONObject txnHolder = new JSONObject();

		txnHolder.put("txnType", "CSDP");
		txnHolder.put("customerId", pMessage.getHeader().getUserId());
		txnHolder.put("txnRefNo", txnRefNo);
		txnHolder.put("fromAccNo", lAccNumber);
		txnHolder.put("toAccNo", "");
		txnHolder.put("txnAmt", lAmount);
		txnHolder.put("benId", "");

		txnHolderNode.put("RequestJson", txnHolder);
		LOG.debug("request prepared for prepareLimitCheckAfterAccOpen.."+txnHolderNode);
		pMessage.getRequestObject().setRequestJson(txnHolderNode);

	}

	public void convertToString(JSONObject obj) {
		LOG.debug("Inside convertToString....");
		try {
			long CIFIdLong = 0;
			CIFIdLong = obj.getJSONObject("executeFinacleScript_CustomData").getLong("CIF_ID");
			obj.getJSONObject("executeFinacleScript_CustomData").remove("CIF_ID");
			obj.getJSONObject("executeFinacleScript_CustomData").put("CIF_ID", String.valueOf(CIFIdLong));
		} catch (Exception ex) {
			LOG.error("Exception Occured ", ex);
			/*LOG.debug("[FrameworkServices] => Exception  ...." + ex.getMessage());
			LOG.debug("[FrameworkServices] => Exception  ...." + ex.getLocalizedMessage());*/
		}
		try {
			long accoutnNumber = 0;
			accoutnNumber = obj.getJSONObject("executeFinacleScript_CustomData").getLong("SBA_ACCOUNT_NUMBER");
			obj.getJSONObject("executeFinacleScript_CustomData").remove("SBA_ACCOUNT_NUMBER");
			obj.getJSONObject("executeFinacleScript_CustomData").put("SBA_ACCOUNT_NUMBER",
					String.valueOf(accoutnNumber));
		} catch (Exception ex) {
			LOG.error("Exception Occured ", ex);
			LOG.debug("[FrameworkServices] => Exception  ...." + ex.getMessage());
			LOG.debug("[FrameworkServices] => Exception  ...." + ex.getLocalizedMessage());
		}
		try {
			long lTRAN_ID = 0;
			lTRAN_ID = obj.getJSONObject("executeFinacleScript_CustomData").getLong("TRAN_ID");
			obj.getJSONObject("executeFinacleScript_CustomData").remove("TRAN_ID");
			obj.getJSONObject("executeFinacleScript_CustomData").put("TRAN_ID", String.valueOf(lTRAN_ID));
		} catch (Exception ex) {
			LOG.error("Exception Occured ", ex);
			/*LOG.debug("[FrameworkServices] => Exception  ...." + ex.getMessage());
			LOG.debug("[FrameworkServices] => Exception  ...." + ex.getLocalizedMessage());*/
		}
	}

	public JSONObject buildResponse(String messagedate, String ltranID, String extMsg, String app, String intMsgCd,
			String laccountNumber, String lcifid, String status) {
		JSONObject respobj = new JSONObject();
		JSONObject accountOpnRespDtls = new JSONObject();
		try {
			respobj.put("data", messagedate);
			respobj.put("txnRefNo", ltranID);
			respobj.put("extMsg", extMsg);
			respobj.put("respCd", app);
			respobj.put("intMsgCd", intMsgCd);
			respobj.put("accountNumber", laccountNumber);
			respobj.put("customerId", lcifid);
			respobj.put("status", status);

			accountOpnRespDtls.put("accountOpnRespDtls", respobj);
		} catch (JSONException e) {
			LOG.error("Exception Occured ", e);
			/*LOG.debug("[FrameworkServices] => Exception  ...." + e.getMessage());
			LOG.debug("[FrameworkServices] => Exception  ...." + e.getLocalizedMessage());*/
		}

		return accountOpnRespDtls;
	}

	@SuppressWarnings("unchecked")
	private String getBankId(Message pMessage, String pAppParamCode) {

		EntityManager em = null;
		String lBankId = null;
		Utils lUtils = new Utils();
		try {
			em = lUtils.getEntityManager();
			Query ltbAgmiAppParamsNQ = em.createNamedQuery("Custom.com.tbAgmiAppParams.Select.PK");
			ltbAgmiAppParamsNQ.setParameter("Param1", pMessage.getHeader().getAppId());
			ltbAgmiAppParamsNQ.setParameter("Param2", pAppParamCode);
			LOG.info(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "Fetching BankId--->");

			List<tbAgmiAppParams> ltbAgmiAppParamsNQResult = ltbAgmiAppParamsNQ.getResultList();
			LOG.debug("Size--------" + ltbAgmiAppParamsNQResult.size());
			LOG.debug("..........." + ltbAgmiAppParamsNQResult.get(0).toString());
			if (ltbAgmiAppParamsNQResult.size() != 0) {
				lBankId = ltbAgmiAppParamsNQResult.get(0).getAppParamValue();
			}
		} catch (Exception ex) {
			LOG.error("Exception Occured ", ex);
			/*LOG.debug("[FrameworkServices] => Exception  ...." + ex.getMessage());
			LOG.debug("[FrameworkServices] => Exception  ...." + ex.getLocalizedMessage());*/
		} finally {
			if (em != null) {
				em.close();
			}
		}
		return lBankId;
	}

	private String insertMasterTable(Message pMessage, String pCustomerId, BigDecimal pAmount, String pTxnType) {
		LOG.debug("Inside insertMasterTable..");
		Utils lutils = new Utils();
		String lrefnum = lutils.CreateTxnMaster(pMessage, pCustomerId, pAmount, pTxnType);
		LOG.debug("lrefnum is.." + lrefnum);
		return lrefnum;
	}

	private JSONObject createServiceReq(Message pMessage, SpringCamelContext pContext, String lBankId, String lrefno) {
		JSONObject lNodeReq = new JSONObject();
		lNodeReq = pMessage.getRequestObject().getRequestJson().getJSONObject("AccOpen");
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.sss");
		String lChannelId = "COR";
		String lLangId = "";
		String lEntityId = "";
		String lEntityType = "";
		String lTimeZone = "";
		String lArmCorrelationId = "";
		String lFICertToken = "";
		String lSSOTransferToken = "";
		String lUserId = "";
		String lPassword = "";
		String lRealUserLoginSessionId = "";
		String lRealUser = "";
		String lRealUserPwd = "";
		String lReqId = Constants.ACCOPENINGREQUESTID;
		String lSchemaLocation = Constants.CUSTOMER360SCHEMALOCATION;

		SimpleDateFormat dfc = new SimpleDateFormat("dd-MM-yyyy");
		JSONObject lcustinfo = lNodeReq.getJSONObject("Custinfo");

		Date lDateDOB = null;
		String lDOB = "";
		String[] lCont;
		String lBirthDt = "";
		String lBirthMnth = "";
		String lBirthYear = "";

		String ldob = "";
		if (lcustinfo.has("DOB")) {
			ldob = lcustinfo.getString("DOB");
		}

		try {
			lDateDOB = dfc.parse(ldob);
			lDOB = dfc.format(lDateDOB);
			lCont = lDOB.split("-");
			lBirthDt = lCont[0];
			lBirthMnth = lCont[1];
			lBirthYear = lCont[2];
		} catch (JSONException e) {
			LOG.error("Exception Occured ", e);
			/*LOG.debug("[FrameworkServices] => Exception  ...." + e.getMessage());
			LOG.debug("[FrameworkServices] => Exception  ...." + e.getLocalizedMessage());*/		} catch (ParseException e) {
		}

		JSONObject lFinalReq = new JSONObject();
		JSONObject lJsonObj = new JSONObject();
		JSONObject lreqHeader = new JSONObject();
		Date lcurrDate = new Date();

		// Message Key
		JSONObject lMsgKey = new JSONObject();
		lMsgKey.put("ServiceRequestVersion", Constants.FinCustomerServiceReqVersion);
		lMsgKey.put("RequestUUID", Utils.getUUID());
		lMsgKey.put("ChannelId", lChannelId);
		lMsgKey.put("LanguageId", lLangId);
		lMsgKey.put("ServiceRequestId", Constants.FinCustomerService360ReqId);

		// Message Info
		JSONObject lMsgInfo = new JSONObject();
		lMsgInfo.put("EntityId", lEntityId);
		lMsgInfo.put("EntityType", lEntityType);
		lMsgInfo.put("MessageDateTime", sdf.format(lcurrDate).toString());
		lMsgInfo.put("TimeZone", lTimeZone);
		lMsgInfo.put("ArmCorrelationId", lArmCorrelationId);
		lMsgInfo.put("BankId", lBankId);

		// Security
		JSONObject lSecurity = new JSONObject();
		lSecurity.put("FICertToken", lFICertToken);
		lSecurity.put("SSOTransferToken", lSSOTransferToken);
		JSONObject lTokenJSON = new JSONObject();
		JSONObject lPwdToken = new JSONObject();
		lPwdToken.put("UserId", lUserId);
		lPwdToken.put("Password", lPassword);
		lTokenJSON.put("PasswordToken", lPwdToken);
		lSecurity.put("Token", lTokenJSON);
		lSecurity.put("RealUserLoginSessionId", lRealUserLoginSessionId);
		lSecurity.put("RealUser", lRealUser);
		lSecurity.put("RealUserPwd", lRealUserPwd);

		JSONObject lReqHeader = new JSONObject();
		lReqHeader.put("MessageKey", lMsgKey);
		lReqHeader.put("RequestMessageInfo", lMsgInfo);
		lReqHeader.put("Security", lSecurity);

		JSONObject lBody = new JSONObject();
		JSONObject lexecuteFinacleScriptRequest = new JSONObject();
		JSONObject lreqId = new JSONObject();
		JSONObject lexecuteFinacleScript_CustomData = new JSONObject();

		LOG.debug("Getting values for Custinfo..");

		String lname = "";
		if (lcustinfo.has("Name")) {
			lname = lcustinfo.getString("Name");
		}
		String lGender = "";
		if (lcustinfo.has("Gender")) {
			lGender = lcustinfo.getString("Gender");
			if (lGender.equals("Female")) {
				lGender = "F";
			} else {
				lGender = "M";
			}
		}

		String laadhar = "";
		if (lcustinfo.has("AadhaarNumber")) {
			laadhar = lcustinfo.getString("AadhaarNumber");
		}

		String lnationality = "";
		if (lcustinfo.has("Nationality")) {
			lnationality = lcustinfo.getString("Nationality");
			if (lnationality.equals("INDIAN")) {
				lnationality = "INDIAN";
			}
		}
		String lmobile = "";
		if (lcustinfo.has("MobileNumber")) {
			lmobile = lcustinfo.getString("MobileNumber");
		}
		String lemail = "";
		if (lcustinfo.has("Email")) {
			lemail = lcustinfo.getString("Email");
		}
		String lhouse = "";
		if (lcustinfo.has("HouseNumber")) {
			lhouse = lcustinfo.getString("HouseNumber");
		}
		String lPermanent_AddrLine1 = "";
		if (lcustinfo.has("HouseLocation")) {
			lPermanent_AddrLine1 = lcustinfo.getString("HouseLocation");
		}
		String lPermanent_AddrLine2 = "";
		if (lcustinfo.has("HouseStreet")) {
			lPermanent_AddrLine2 = lcustinfo.getString("HouseStreet");
		}
		String lstate = "";
		if (lcustinfo.has("HouseState")) {
			lstate = lcustinfo.getString("HouseState");
		}
		String lcity = "";
		if (lcustinfo.has("HouseCity")) {
			lcity = lcustinfo.getString("HouseCity");
		}
		String lpincodes = "";
		BigDecimal lpincode = null;
		if (lcustinfo.has("HousePincode")) {
			lpincodes = lcustinfo.getString("HousePincode");
			if (!lpincodes.equals("")) {
				lpincode = new BigDecimal(lpincodes);
			}
		}
		String ldistrict = "";
		if (lcustinfo.has("HouseDistrict")) {
			ldistrict = lcustinfo.getString("HouseDistrict");
		}
		String llandmark = "";
		if (lcustinfo.has("HouseLandmark")) {
			llandmark = lcustinfo.getString("HouseLandmark");
		}
		String lmaiden_name = "";
		if (lcustinfo.has("Maidenname")) {
			lmaiden_name = lcustinfo.getString("Maidenname");
		}
		String lfather_name = "";
		if (lcustinfo.has("FatherName")) {
			lfather_name = lcustinfo.getString("FatherName");
		}
		String lspouse_name = "";
		if (lcustinfo.has("SpouseName")) {
			lspouse_name = lcustinfo.getString("SpouseName");
		}
		String lmother_name = "";
		if (lcustinfo.has("MotherName")) {
			lmother_name = lcustinfo.getString("MotherName");
		}
		String lresidence = "";
		if (lcustinfo.has("ResidenceNumber")) {
			lresidence = lcustinfo.getString("ResidenceNumber");
		}
		String ltitle = "";
		if (lcustinfo.has("Title")) {
			ltitle = lcustinfo.getString("Title");
		}
		String lform60_61 = "";
		String lpan = "";
		if (lcustinfo.has("PanNumber")) {
			lpan = lcustinfo.getString("PanNumber");
			if (lpan.equals("")) {
				lform60_61 = "Y";
			} else {
				lform60_61 = "N";
			}
		}
		LOG.debug("----------------------------Custinfo---------------------------");
		JSONObject lcommunicationaddr = lNodeReq.getJSONObject("communicationaddr");
		LOG.debug("Getting values for communicationaddr..");

		String laddr1 = "";
		if (lcommunicationaddr.has("addr1")) {
			laddr1 = lcommunicationaddr.getString("addr1");
		}
		String laddr2 = "";
		if (lcommunicationaddr.has("addr2")) {
			laddr2 = lcommunicationaddr.getString("addr2");
		}
		String lcommunicationaddrpincode = "";
		if (lcommunicationaddr.has("pincode")) {
			lcommunicationaddrpincode = lcommunicationaddr.getString("pincode");
		}
		String lcommunicationaddrpincodelocations = "";
		BigDecimal lcommunicationaddrpincodelocation = null;
		if (lcommunicationaddr.has("location")) {
			lcommunicationaddrpincodelocations = lcommunicationaddr.getString("location");
			if (!lcommunicationaddrpincodelocations.equals("")) {
				lcommunicationaddrpincodelocation = new BigDecimal(lcommunicationaddrpincodelocations);
			}
		}
		String llcommunicationaddrcity = "";
		if (lcommunicationaddr.has("city")) {
			llcommunicationaddrcity = lcommunicationaddr.getString("city");
		}

		String llcommunicationaddrDistrict = "";
		if (lcommunicationaddr.has("District")) {
			llcommunicationaddrDistrict = lcommunicationaddr.getString("District");
		}

		String lcommunicationaddrstate = "";
		if (lcommunicationaddr.has("state")) {
			lcommunicationaddrstate = lcommunicationaddr.getString("state");
		}

		String lcommunicationaddrcountryCode = "";
		if (lcommunicationaddr.has("countryCode")) {
			lcommunicationaddrcountryCode = lcommunicationaddr.getString("countryCode");
		}

		String lcommunicationaddrPanAcknowledgementNumber = "";
		if (lcommunicationaddr.has("PanAcknowledgementNumber")) {
			lcommunicationaddrPanAcknowledgementNumber = lcommunicationaddr.getString("PanAcknowledgementNumber");
		}

		String lcommunicationaddrNonAgriculturalIncome = "";
		if (lcommunicationaddr.has("NonAgriculturalIncome")) {
			lcommunicationaddrNonAgriculturalIncome = lcommunicationaddr.getString("NonAgriculturalIncome");
		}

		String lcommunicationaddrAgriculturalIncome = "";
		if (lcommunicationaddr.has("AgriculturalIncome")) {
			lcommunicationaddrAgriculturalIncome = lcommunicationaddr.getString("AgriculturalIncome");
		}

		String lcommunicationaddrPanRemarks = "";
		if (lcommunicationaddr.has("PanRemarks")) {
			lcommunicationaddrPanRemarks = lcommunicationaddr.getString("PanRemarks");
		}

		String lcommunicationaddrPcheck="";
		if(lcommunicationaddr.has("AdressFlag")){
			lcommunicationaddrPcheck=lcommunicationaddr.getString("AdressFlag");
		}
		LOG.debug("---------------------------Communicationadd Completed----------------------------------------");
		JSONObject lNomineeDets = lNodeReq.getJSONObject("NomineeDets");
		LOG.debug("Getting values for NomineeDets..");
		String lNomineeDetsname = "";
		if (lNomineeDets.has("name")) {
			lNomineeDetsname = lNomineeDets.getString("name");
		}
		String lNomineeDetsaddr1 = "";
		if (lNomineeDets.has("NomineeAdd1")) {
			lNomineeDetsaddr1 = lNomineeDets.getString("NomineeAdd1");
		}
		String lNomineeDetsaddr2 = "";
		if (lNomineeDets.has("NomineeAdd2")) {
			lNomineeDetsaddr2 = lNomineeDets.getString("NomineeAdd1");
		}
		String lNomineeDetspincodes = "";
		BigDecimal lNomineeDetspincode = null;
		if (lNomineeDets.has("NomineePincode")) {
			lNomineeDetspincodes = lNomineeDets.getString("NomineePincode");
			if (!lNomineeDetspincodes.equals("")) {
				lNomineeDetspincode = new BigDecimal(lNomineeDetspincodes);
			}
		}
		String lNomineeDetsDOB = "";
		Date date1 = null;
		if (lNomineeDets.has("dob")) {
			lNomineeDetsDOB = lNomineeDets.getString("dob");
			if(!lNomineeDetsDOB.equals("")||!lNomineeDetsDOB.isEmpty()){
			try {
				date1 = new SimpleDateFormat("yyyy-MM-dd").parse(lNomineeDetsDOB);
			} catch (ParseException e) {
			//	LOG.debug("Exception lNomineeDetsDOB " + e.getMessage());
				LOG.error("Exception lNomineeDetsDOB ", e);
			}
			}
		}
		String lNomineeDetsguardian_name = "";
		if (lNomineeDets.has("guardianname")) {
			lNomineeDetsguardian_name = lNomineeDets.getString("guardianname");
		}

		String lguardian_addr1 = "";
		if (lNomineeDets.has("guardianAddr1")) {
			lguardian_addr1 = lNomineeDets.getString("guardianAddr1");
		}
		String lguardian_addr2 = "";
		if (lNomineeDets.has("guardianAddr2")) {
			lguardian_addr2 = lNomineeDets.getString("guardianAddr2");
		}
		String lguardian_pincode = "";
		if (lNomineeDets.has("guardianPincode")) {
			lguardian_pincode = lNomineeDets.getString("guardianPincode");
		}
		String lguardian_dob = "";
		if (lNomineeDets.has("guardianDob")) {
			lguardian_dob = lNomineeDets.getString("guardianDob");
		}
		String lguardian_city = "";
		if (lNomineeDets.has("guardianCity")) {
			lguardian_city = lNomineeDets.getString("guardianCity");
		}
		String lguardian_location = "";
		if (lNomineeDets.has("guardian_location")) {
			lguardian_location = lNomineeDets.getString("guardian_location");
		}
		String lguardian_state = "";
		if (lNomineeDets.has("guardianState")) {
			lguardian_state = lNomineeDets.getString("guardianState");
		}
		String lguardianDistrict = "";
		if (lNomineeDets.has("guardianDistrict")) {
			lguardianDistrict = lNomineeDets.getString("guardianDistrict");
		}
		String lNomineeRelationship = "";
		if (lNomineeDets.has("NomineeRelationship")) {
			lNomineeRelationship = lNomineeDets.getString("NomineeRelationship");

			if (lNomineeRelationship.equalsIgnoreCase("OTHERS")) {
				lNomineeRelationship = "999";
			} else if (lNomineeRelationship.equalsIgnoreCase("ADMINISTRATOR")) {
				lNomineeRelationship = "ADM";
			} else if (lNomineeRelationship.equalsIgnoreCase("AUNT")) {
				lNomineeRelationship = "AUN";
			} else if (lNomineeRelationship.equalsIgnoreCase("BROTHER IN LAW")) {
				lNomineeRelationship = "BIL";
			} else if (lNomineeRelationship.equalsIgnoreCase("BROTHER")) {
				lNomineeRelationship = "BRO";
			} else if (lNomineeRelationship.equalsIgnoreCase("COUSIN BROTHER")) {
				lNomineeRelationship = "COB";
			} else if (lNomineeRelationship.equalsIgnoreCase("CO-DIRECTOR")) {
				lNomineeRelationship = "COD";
			} else if (lNomineeRelationship.equalsIgnoreCase("CO-PARTNERS")) {
				lNomineeRelationship = "COP";
			} else if (lNomineeRelationship.equalsIgnoreCase("COUSIN SISTER")) {
				lNomineeRelationship = "COS";
			} else if (lNomineeRelationship.equalsIgnoreCase("DAUGHTER")) {
				lNomineeRelationship = "DAU";
			} else if (lNomineeRelationship.equalsIgnoreCase("DAUGHTER IN LAW")) {
				lNomineeRelationship = "DIL";
			} else if (lNomineeRelationship.equalsIgnoreCase("EXECUTOR")) {
				lNomineeRelationship = "EXE";
			} else if (lNomineeRelationship.equalsIgnoreCase("FATHER")) {
				lNomineeRelationship = "FAT";
			} else if (lNomineeRelationship.equalsIgnoreCase("FATHER IN LAW")) {
				lNomineeRelationship = "FIL";
			} else if (lNomineeRelationship.equalsIgnoreCase("GRAND FATHER (MATERNAL)")) {
				lNomineeRelationship = "GFM";
			} else if (lNomineeRelationship.equalsIgnoreCase("GRAND FATHER (PATERNAL)")) {
				lNomineeRelationship = "GFP";
			} else if (lNomineeRelationship.equalsIgnoreCase("GRAND MOTHER (MATERNAL)")) {
				lNomineeRelationship = "GMM";
			} else if (lNomineeRelationship.equalsIgnoreCase("GRAND MOTHER (PATERNAL)")) {
				lNomineeRelationship = "GMP";
			} else if (lNomineeRelationship.equalsIgnoreCase("GRAND SON")) {
				lNomineeRelationship = "GRASO";
			} else if (lNomineeRelationship.equalsIgnoreCase("GUARANTOR")) {
				lNomineeRelationship = "GUA";
			} else if (lNomineeRelationship.equalsIgnoreCase("HOLDING  CO")) {
				lNomineeRelationship = "HOL";
			} else if (lNomineeRelationship.equalsIgnoreCase("HUSBAND")) {
				lNomineeRelationship = "HUS";
			} else if (lNomineeRelationship.equalsIgnoreCase("LEGAL GUARDIAN")) {
				lNomineeRelationship = "LEG";
			} else if (lNomineeRelationship.equalsIgnoreCase("MOTHER IN LAW")) {
				lNomineeRelationship = "MIL";
			} else if (lNomineeRelationship.equalsIgnoreCase("MANDATEE")) {
				lNomineeRelationship = "MNDT";
			} else if (lNomineeRelationship.equalsIgnoreCase("MOTHER")) {
				lNomineeRelationship = "MOT";
			} else if (lNomineeRelationship.equalsIgnoreCase("NIECE")) {
				lNomineeRelationship = "NEE";
			} else if (lNomineeRelationship.equalsIgnoreCase("NEPHEW")) {
				lNomineeRelationship = "NEP";
			} else if (lNomineeRelationship.equalsIgnoreCase("NATURAL GUARDIAN")) {
				lNomineeRelationship = "NGU";
			} else if (lNomineeRelationship.equalsIgnoreCase("POWER OF ATTORNEY HOLDER")) {
				lNomineeRelationship = "POA";
			} else if (lNomineeRelationship.equalsIgnoreCase("SON IN LAW")) {
				lNomineeRelationship = "SIL";
			} else if (lNomineeRelationship.equalsIgnoreCase("SISTER")) {
				lNomineeRelationship = "SIS";
			} else if (lNomineeRelationship.equalsIgnoreCase("SON")) {
				lNomineeRelationship = "SON";
			} else if (lNomineeRelationship.equalsIgnoreCase("WIFE")) {
				lNomineeRelationship = "WIF";
			}

		}
		String lNomineeCity = "";
		if (lNomineeDets.has("NomineeCity")) {
			lNomineeCity = lNomineeDets.getString("NomineeCity");
		}
		String lNomineeDistrict = "";
		if (lNomineeDets.has("NomineeDistrict")) {
			lNomineeDistrict = lNomineeDets.getString("NomineeDistrict");
		}
		String lNomineeState = "";
		if (lNomineeDets.has("NomineeState")) {
			lNomineeState = lNomineeDets.getString("NomineeState");
		}
		String lNomineeisMinor = "";
		if (lNomineeDets.has("isMinor")) {
			lNomineeisMinor = lNomineeDets.getString("isMinor");
		}
		String lNomineeCountryCode = "";
		if (lNomineeDets.has("NomineeCountryCode")) {
			lNomineeCountryCode = lNomineeDets.getString("NomineeCountryCode");
		}
		String lMinorFalg = "";
		if (lNomineeDets.has("isMinor")) {
			lMinorFalg = lNomineeDets.getString("isMinor");
		}

		String lNomineeAadhaar = "";
		if (lNomineeDets.has("NomineeAadhaar")) {
			lNomineeAadhaar = lNomineeDets.getString("NomineeAadhaar");
		}

		String lRelationship = "";
		if (lNomineeDets.has("Relationship")) {
			lRelationship = lNomineeDets.getString("Relationship");
			if (lRelationship.equalsIgnoreCase("Court Appointed")) {
				lRelationship = "C";
			} else if (lRelationship.equalsIgnoreCase("Father")) {
				lRelationship = "F";
			} else if (lRelationship.equalsIgnoreCase("Mother")) {
				lRelationship = "M";
			} else if (lRelationship.equalsIgnoreCase("Others")) {
				lRelationship = "O";
			}
		}

		LOG.debug("-----------------------------NomineeDets Completed--------------------------------------");
		JSONObject lAdditionalInfo = lNodeReq.getJSONObject("AdditionalInfo");
		LOG.debug("Getting values for AdditionalInfo..");

		String lAdditionalInfoplace_birth = "";
		if (lAdditionalInfo.has("PlaceOfBirth")) {
			lAdditionalInfoplace_birth = lAdditionalInfo.getString("PlaceOfBirth");
		}

		String lAdditionalInforeligion = "";
		if (lAdditionalInfo.has("Religion")) {
			lAdditionalInforeligion = lAdditionalInfo.getString("Religion");
			if (lAdditionalInforeligion.equalsIgnoreCase("HINDU")) {
				lAdditionalInforeligion = "HINDU";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("MINORITY COM - CHRISTIANS")) {
				lAdditionalInforeligion = "MCCHR";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("CHRISTIAN")) {
				lAdditionalInforeligion = "CHRIS";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("MINORITY COM - MUSLIMS")) {
				lAdditionalInforeligion = "MCMUS";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("MINORITY COM - NEO BUDDHISTS")) {
				lAdditionalInforeligion = "MCNBU";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("BUDDHIST")) {
				lAdditionalInforeligion = "BUDHI";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("MINORITY COM - SIKHS")) {
				lAdditionalInforeligion = "MCSIK";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("MINORITY COM - ZORASTRIANS")) {
				lAdditionalInforeligion = "MCZOR";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("MUSLIM")) {
				lAdditionalInforeligion = "MUSLI";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("ALL OTHER COMMUNITIES")) {
				lAdditionalInforeligion = "OTHER";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("PARSI")) {
				lAdditionalInforeligion = "PARSI";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("SIKH")) {
				lAdditionalInforeligion = "SIKHS";
			} else if (lAdditionalInforeligion.equalsIgnoreCase("ZORASTRIAN")) {
				lAdditionalInforeligion = "ZORAS";
			}
		}
		String lAdditionalInfonationality = "";
		if (lAdditionalInfo.has("Nationality")) {
			lAdditionalInfonationality = lAdditionalInfo.getString("Nationality");
		}
		String lAdditionalInforesidential_status = "";
		if (lAdditionalInfo.has("ResidentialStatus")) {
			lAdditionalInforesidential_status = lAdditionalInfo.getString("ResidentialStatus");
		}
		String lAdditionalInfomarital_status = "";
		if (lAdditionalInfo.has("MaritalStatus")) {
			lAdditionalInfomarital_status = lAdditionalInfo.getString("MaritalStatus");
			if (lAdditionalInfomarital_status.equalsIgnoreCase("Married")) {
				lAdditionalInfomarital_status = "MARR";
			} else if (lAdditionalInfomarital_status.equalsIgnoreCase("UnMarried")) {
				lAdditionalInfomarital_status = "UNMAR";
			} else if (lAdditionalInfomarital_status.equalsIgnoreCase("DIVORCED")) {
				lAdditionalInfomarital_status = "DIVOR";
			} else if (lAdditionalInfomarital_status.equalsIgnoreCase("WIDOW")) {
				lAdditionalInfomarital_status = "WIDOW";
			} else if (lAdditionalInfomarital_status.equalsIgnoreCase("WIDOWER")) {
				lAdditionalInfomarital_status = "WIDWR";
			} else if (lAdditionalInfomarital_status.equalsIgnoreCase("LEGALLY SEPARATED")) {
				lAdditionalInfomarital_status = "LEGSP";
			} else if (lAdditionalInfomarital_status.equalsIgnoreCase("LIVE-IN RELATIONSHIP")) {
				lAdditionalInfomarital_status = "LIVTO";
			}
		}
		String lAdditionalInfooccupation_type = "";
		if (lAdditionalInfo.has("OccupationType")) {
			lAdditionalInfooccupation_type = lAdditionalInfo.getString("OccupationType");
			if (lAdditionalInfooccupation_type.equalsIgnoreCase("AGRI. & ALLIED ACTIVITIES")) {
				lAdditionalInfooccupation_type = "001";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("AGRICULTURE AND ALLIED ACTIVITIES")) {
				lAdditionalInfooccupation_type = "AGRAA";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("ARCHITECT")) {
				lAdditionalInfooccupation_type = "ARCHT";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("CHARTERED ACCOUNTANT")) {
				lAdditionalInfooccupation_type = "CHART";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("DOCTOR")) {
				lAdditionalInfooccupation_type = "DOCTR";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("ENGINEERING CONSULTANT")) {
				lAdditionalInfooccupation_type = "ENGCT";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("ENTREPRENEUR")) {
				lAdditionalInfooccupation_type = "ENTPR";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("PVT EMPLOYEE")) {
				lAdditionalInfooccupation_type = "EOPVT";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("PSU EMPLOYEE")) {
				lAdditionalInfooccupation_type = "EPSUS";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("FINANCE")) {
				lAdditionalInfooccupation_type = "FINCE";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("HOUSEWIFE")) {
				lAdditionalInfooccupation_type = "HOUWF";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("INDUSTRIALIST")) {
				lAdditionalInfooccupation_type = "INDUS";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("LAWYERS / ADVOCATES")) {
				lAdditionalInfooccupation_type = "LWYRS";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("PUBLIC UTILITIES AND SERVICES")) {
				lAdditionalInfooccupation_type = "PUBUS";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("STUDENT")) {
				lAdditionalInfooccupation_type = "STUDT";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("TRADER")) {
				lAdditionalInfooccupation_type = "TRADR";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("TRANSPORT")) {
				lAdditionalInfooccupation_type = "TRAPT";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("EXECUTIVE")) {
				lAdditionalInfooccupation_type = "Executive";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("MANAGERIAL")) {
				lAdditionalInfooccupation_type = "Managerial";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("PROFESSIONAL")) {
				lAdditionalInfooccupation_type = "Professional";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("ACADEMIC")) {
				lAdditionalInfooccupation_type = "Academic";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("COMPUTER RELATED")) {
				lAdditionalInfooccupation_type = "Computer Related";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("ENGINEERING")) {
				lAdditionalInfooccupation_type = "Engineering";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("OTHER TECHNICAL")) {
				lAdditionalInfooccupation_type = "Other Technical";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("SERVICE")) {
				lAdditionalInfooccupation_type = "Service";
			} else if (lAdditionalInfooccupation_type.equalsIgnoreCase("CLERICAL")) {
				lAdditionalInfooccupation_type = "Clerical";
			}
			 else if (lAdditionalInfooccupation_type.equalsIgnoreCase("SALES")) {
				lAdditionalInfooccupation_type = "Sales";
			}else if(lAdditionalInfooccupation_type.equalsIgnoreCase("GOVT EMPLOYEE")){
				lAdditionalInfooccupation_type="EGOVT";
			}
		}
		String lAdditionalInfoeducation_level = "";
		if (lAdditionalInfo.has("EducationLevel")) {
			lAdditionalInfoeducation_level = lAdditionalInfo.getString("EducationLevel");
		}
		String lAdditionalInfogross_annual_income = "";
		if (lAdditionalInfo.has("GrossAnnualIncome")) {
			lAdditionalInfogross_annual_income = lAdditionalInfo.getString("GrossAnnualIncome");
			LOG.debug("AdditionalInfogross annual income: " + lAdditionalInfogross_annual_income);
		}

		String lAdditionalInfoPassportNumber = "";
		if (lAdditionalInfo.has("PassportNumber")) {
			lAdditionalInfoPassportNumber = lAdditionalInfo.getString("PassportNumber");
		}
		String lAdditionalInfoDrivingLicence = "";
		if (lAdditionalInfo.has("DrivingLicence")) {
			lAdditionalInfoDrivingLicence = lAdditionalInfo.getString("DrivingLicence");
		}

		String lAdditionalInfoVoterId = "";
		if (lAdditionalInfo.has("VoterId")) {
			lAdditionalInfoVoterId = lAdditionalInfo.getString("VoterId");
		}

		String lAdditionalInfoPoliticallyExposedPerson = "";
		if (lAdditionalInfo.has("PoliticallyExposedPerson")) {
			lAdditionalInfoPoliticallyExposedPerson = lAdditionalInfo.getString("PoliticallyExposedPerson");
		}

		String lAdditionalInfoPOSBCIFNumber="";
		if(lAdditionalInfo.has("POSBCIFNUMBER")){
			lAdditionalInfoPOSBCIFNumber=lAdditionalInfo.getString("POSBCIFNUMBER");
		}
		String lAdditionalInfoAadhaarConfId="";
		if(lAdditionalInfo.has("AadhaarConfId")){
			lAdditionalInfoAadhaarConfId=lAdditionalInfo.getString("AadhaarConfId");
		}
		String lAdditionalInfoFacilityId="";
		if(lAdditionalInfo.has("FacilityId")){
			lAdditionalInfoFacilityId=lAdditionalInfo.getString("FacilityId");
		}
		
		String lAdditionalInfoposbcifTnc="";
		if(lAdditionalInfo.has("posbcifTnC")){
			lAdditionalInfoposbcifTnc=lAdditionalInfo.getString("posbcifTnC");
			LOG.debug("posbcifTnC:"+lAdditionalInfoposbcifTnc);
		}
		String lEXTCUST="";
		if(lAdditionalInfo.has("EXTCUST")){
			lEXTCUST=lAdditionalInfo.getString("EXTCUST");
			LOG.debug("lEXTCUST:"+lEXTCUST);
		}
		String lPAN_NAME="";
		if(lAdditionalInfo.has("PAN_NAME")){
			lPAN_NAME=lAdditionalInfo.getString("PAN_NAME");
			LOG.debug("lPAN_NAME:"+lPAN_NAME);
		}
		String lPanMatchFlg="";
		if(lAdditionalInfo.has("PanMatchFlg")){
			lPanMatchFlg=lAdditionalInfo.getString("PanMatchFlg");
			LOG.debug("lPanMatchFlg:"+lPanMatchFlg);
		}
		String lPanMatchPercentage="";
		if(lAdditionalInfo.has("PanMatchPercentage")){
			lPanMatchPercentage=lAdditionalInfo.getString("PanMatchPercentage");
			LOG.debug("lPanMatchPercentage:"+lAdditionalInfoposbcifTnc);
		}
		
		LOG.debug("-----------------------------AdditionalInfo Completed--------------------------------------");

		JSONObject lAccountInformation = lNodeReq.getJSONObject("AccountInformation");
		LOG.debug("Getting values for AccountInformation..");

		String lAccountInformationSweepin_Account_Number = "";
		if (lAccountInformation.has("SweepOutAccountNumber")) {
			lAccountInformationSweepin_Account_Number = lAccountInformation.getString("SweepOutAccountNumber");
		}
		String lAccountInformationSweepin_Bank = "";
		if (lAccountInformation.has("SweepOutBank")) {
			lAccountInformationSweepin_Bank = lAccountInformation.getString("SweepOutBank");
		}
		String lAccountInformationSweepin_Branch = "";
		if (lAccountInformation.has("SweepOutBranch")) {
			lAccountInformationSweepin_Branch = lAccountInformation.getString("SweepOutBranch");
		}
		String lAccountInformationInitial_Dep_Amount = "";
		if (lAccountInformation.has("InitialDepAmount")) {
			lAccountInformationInitial_Dep_Amount = lAccountInformation.getString("InitialDepAmount");
		}
		String lAccountInformationwelcomeKitId = "";
		if (lAccountInformation.has("WelcomeKitID")) {
			lAccountInformationwelcomeKitId = lAccountInformation.getString("WelcomeKitID");
		}

		String lAccountInformationAccountStatement = "";
		if (lAccountInformation.has("AccountStatement")) {
			lAccountInformationAccountStatement = lAccountInformation.getString("AccountStatement");
		}

		String lAccountInformationModeOfDelivery = "";
		if (lAccountInformation.has("ModeOfDelivery")) {
			lAccountInformationModeOfDelivery = lAccountInformation.getString("ModeOfDelivery");
		}

		String lAccountInformationModeOfOperation = "";
		if (lAccountInformation.has("ModeOfOperation")) {
			lAccountInformationModeOfOperation = lAccountInformation.getString("ModeOfOperation");
			if (lAccountInformationModeOfOperation.equalsIgnoreCase("SELF")) {
				lAccountInformationModeOfOperation = "SELF";
			} else if (lAccountInformationModeOfOperation.equalsIgnoreCase("SINGLE")) {
				lAccountInformationModeOfOperation = "SINGLE";
			} else if (lAccountInformationModeOfOperation.equalsIgnoreCase("EITHER OR SURVIVOUR")) {
				lAccountInformationModeOfOperation = "ES";
			}
		}

		String lAccountInformationProductName = "";
		if (lAccountInformation.has("ProductName")) {
			lAccountInformationProductName = lAccountInformation.getString("ProductName");
			if (lAccountInformationProductName.equalsIgnoreCase("SBREG - Regular Savings Account")) {
				lAccountInformationProductName = "SBREG";
			} else if (lAccountInformationProductName.equalsIgnoreCase("CAREG - CA-Proprietor")) {
				lAccountInformationProductName = "CAREG";
			} else if (lAccountInformationProductName.equalsIgnoreCase("DGSBA - Digital Savings Account")) {
				lAccountInformationProductName = "DGSBA";
			}else if (lAccountInformationProductName.equalsIgnoreCase("REGULAR SAVINGS ACCOUNT")) {
				lAccountInformationProductName = "SBREG";
			}else if (lAccountInformationProductName.equalsIgnoreCase("BASIC SAVINGS ACCOUNT")) {
				lAccountInformationProductName = "BSBDA";
			}else if (lAccountInformationProductName.equalsIgnoreCase("CURRENT ACCOUNT")) {
				lAccountInformationProductName = "CAREG";
			}else if (lAccountInformationProductName.equalsIgnoreCase("SAVINGS ACCOUNT DOP AGENTS")) {
				lAccountInformationProductName = "SBDOP";
			}
			
		}

		String lAccountInformationOperatinginstructions = "";
		if (lAccountInformation.has("Operatinginstructions")) {
			lAccountInformationOperatinginstructions = lAccountInformation.getString("Operatinginstructions");
		}

		String lAccountInformationDoorStepBanking = "";
		if (lAccountInformation.has("DoorStepBanking")) {
			lAccountInformationDoorStepBanking = lAccountInformation.getString("DoorStepBanking");
		}
		String lAccountInformationIBEnrollment = "";
		if (lAccountInformation.has("IBEnrollment")) {
			lAccountInformationIBEnrollment = lAccountInformation.getString("IBEnrollment");
		}
		String lAccountInformationIVREnrollment = "";
		if (lAccountInformation.has("IVREnrollment")) {
			lAccountInformationIVREnrollment = lAccountInformation.getString("IVREnrollment");
		}
		String lAccountInformationMBEnrollment = "";
		if (lAccountInformation.has("MBEnrollment")) {
			lAccountInformationMBEnrollment = lAccountInformation.getString("MBEnrollment");
		}
		String lAdditionalInfoModeOfPayment = "";
		if (lAccountInformation.has("ModeOfPayment")) {
			lAdditionalInfoModeOfPayment = lAccountInformation.getString("ModeOfPayment");
		}

		String lLinkAadhaar = "";
		if (lAccountInformation.has("LinkAadhaar")) {
			lLinkAadhaar = lAccountInformation.getString("LinkAadhaar");
		}

		String lSMSBanking = "";
		if (lAccountInformation.has("SMSBanking")) {
			lSMSBanking = lAccountInformation.getString("SMSBanking");
		}

		String lPOSB = "";
		if (lAccountInformation.has("POSB")) {
			lPOSB = lAccountInformation.getString("POSB");
		}

		String lMissedCallBanking = "";
		if (lAccountInformation.has("MissedCallBanking")) {
			lMissedCallBanking = lAccountInformation.getString("MissedCallBanking");
		}

		String lDeclarationCheck = "";
		if (lAccountInformation.has("DeclarationCheck")) {
			lDeclarationCheck = lAccountInformation.getString("DeclarationCheck");
		}

		String lwelcomeKitProvided = "";
		if (lAccountInformation.has("welcomeKitProvided")) {
			lwelcomeKitProvided = lAccountInformation.getString("welcomeKitProvided");
		}
		if (lAccountInformationwelcomeKitId.equals("")) {
			lwelcomeKitProvided = "N";
		} else {
			lwelcomeKitProvided = "Y";
		}

		String lSignature = "";
		if (lAccountInformation.has("Signature")) {
			lSignature = lAccountInformation.getString("Signature");
		}
		String lInvestment = "";
		if (lAccountInformation.has("Investment")) {
			lInvestment = lAccountInformation.getString("Investment");
		}

		String lReferenceNum = "";
		if (lAccountInformation.has("ReferenceNum")) {
			lReferenceNum = lAccountInformation.getString("ReferenceNum");
		}

		String lRelationshipManager = "";
		if (lAccountInformation.has("RelationshipManager")) {
			lRelationshipManager = lAccountInformation.getString("RelationshipManager");
		}

		String lTypeDesc = "";
		if (lAccountInformation.has("TypeDesc")) {
			lTypeDesc = lAccountInformation.getString("TypeDesc");
		}
		String lIIN="";
		if (lAccountInformation.has("IIN")) {
			lIIN = lAccountInformation.getString("IIN");
			LOG.debug("lIIN from the request is ::"+lIIN); 
			if(lIIN!=null||!lIIN.equals(""))
			{
				lIIN=getIINCode(lIIN);
			}
		}
		

		LOG.debug("-----------------------------AccountInformation Completed--------------------------------------");
		
		
		StringBuilder lconsentVal = new StringBuilder();

		if(lNodeReq.has("consentVal")){
		LOG.debug("Request has consentVal"); 	
		JSONArray lconsentValres=lNodeReq.getJSONArray("consentVal");
		int size=lconsentValres.length();
		LOG.debug(""+size);
		for(int i=0;i<size;i++){
			String	msg2=lconsentValres.getString(i);
			if((i==size-1)){
				lconsentVal.append(msg2);
			}else{
				lconsentVal.append(msg2).append(",");
			}
		}
		LOG.debug("lconsentVal ::"+lconsentVal);
		lexecuteFinacleScript_CustomData.put("ConsentVal", lconsentVal.toString());
		}
		
		lexecuteFinacleScript_CustomData.put("posbcifTnC", lAdditionalInfoposbcifTnc);
		lexecuteFinacleScript_CustomData.put("Title", ltitle);
		lexecuteFinacleScript_CustomData.put("FirstName", lname); //
		lexecuteFinacleScript_CustomData.put("MiddleName", "");
		lexecuteFinacleScript_CustomData.put("LastName", ""); //
		lexecuteFinacleScript_CustomData.put("MaidenName", lmaiden_name);
		lexecuteFinacleScript_CustomData.put("AadharNo", laadhar);
		lexecuteFinacleScript_CustomData.put("DateOfBirth", sdf.format(lDateDOB));
		// lexecuteFinacleScript_CustomData.put("DateOfBirth",
		// "1992-01-08T00:00:00.000");
		lexecuteFinacleScript_CustomData.put("PlaceOfBirth", lAdditionalInfoplace_birth);
		lexecuteFinacleScript_CustomData.put("MinorFlag", lMinorFalg);
		lexecuteFinacleScript_CustomData.put("Religion", lAdditionalInforeligion);
		lexecuteFinacleScript_CustomData.put("Gender", lGender);
		lexecuteFinacleScript_CustomData.put("RelationshipOpeningDt", sdf.format(lcurrDate).toString());// check
		lexecuteFinacleScript_CustomData.put("MobileNum", lmobile); //
		lexecuteFinacleScript_CustomData.put("ResidenceTelePh", lresidence);
		lexecuteFinacleScript_CustomData.put("EmailId", lemail); //
		lexecuteFinacleScript_CustomData.put("Permanent_AddrLine1", lhouse+ " " + lPermanent_AddrLine1);
		lexecuteFinacleScript_CustomData.put("Permanent_AddrLine2", lPermanent_AddrLine2+ " " +llandmark);
		lexecuteFinacleScript_CustomData.put("Permanent_City_Town_Village", lcity);
		lexecuteFinacleScript_CustomData.put("Permanent_District", ldistrict);
		lexecuteFinacleScript_CustomData.put("Permanent_State", lstate);
		lexecuteFinacleScript_CustomData.put("Permanent_PinCode", lpincode);
		lexecuteFinacleScript_CustomData.put("Permanent_ResidentialStatus", lAdditionalInforesidential_status);
		if(lcommunicationaddrPcheck.equals("y")){
			lexecuteFinacleScript_CustomData.put("Communication_AddrLine1", lhouse+ " " +lPermanent_AddrLine1);
			lexecuteFinacleScript_CustomData.put("Communication_AddrLine2", lPermanent_AddrLine2+ " " +llandmark);
		}else{
			lexecuteFinacleScript_CustomData.put("Communication_AddrLine1", laddr1);
			lexecuteFinacleScript_CustomData.put("Communication_AddrLine2", laddr2);
		}
		lexecuteFinacleScript_CustomData.put("Communication_City_Town_Village", llcommunicationaddrcity);
		lexecuteFinacleScript_CustomData.put("Communication_District", llcommunicationaddrDistrict);
		lexecuteFinacleScript_CustomData.put("Communication_State", lcommunicationaddrstate);
		lexecuteFinacleScript_CustomData.put("Communication_PinCode", lcommunicationaddrpincode);
		lexecuteFinacleScript_CustomData.put("Communication_ResidentialStatus", "");
		lexecuteFinacleScript_CustomData.put("Nationality", lnationality);
		lexecuteFinacleScript_CustomData.put("MaritalStatus", lAdditionalInfomarital_status);
		lexecuteFinacleScript_CustomData.put("Occupation_Type", lAdditionalInfooccupation_type);
		lexecuteFinacleScript_CustomData.put("GrossAnnualIncome", lAdditionalInfogross_annual_income);
		lexecuteFinacleScript_CustomData.put("EducationLevel", lAdditionalInfoeducation_level);
		lexecuteFinacleScript_CustomData.put("PAN", lpan); //
		lexecuteFinacleScript_CustomData.put("PassportNum", lAdditionalInfoPassportNumber);
		lexecuteFinacleScript_CustomData.put("DrivingLicenseNum", lAdditionalInfoDrivingLicence);
		lexecuteFinacleScript_CustomData.put("VoterIdCardNum", lAdditionalInfoVoterId);
		lexecuteFinacleScript_CustomData.put("form60_61", lform60_61);
		lexecuteFinacleScript_CustomData.put("Father_SpouseName", lfather_name);
		lexecuteFinacleScript_CustomData.put("MotherName", lmother_name);
		lexecuteFinacleScript_CustomData.put("PEP", lAdditionalInfoPoliticallyExposedPerson);
		lexecuteFinacleScript_CustomData.put("uidaiCode", "");
		lexecuteFinacleScript_CustomData.put("BirthMonth", lBirthMnth);
		lexecuteFinacleScript_CustomData.put("StaffFlag", "N");
		lexecuteFinacleScript_CustomData.put("Permanent_AddrCategory", "");
		lexecuteFinacleScript_CustomData.put("Communication_PrefFormat", "");
		lexecuteFinacleScript_CustomData.put("ShortName", "MTR");
		lexecuteFinacleScript_CustomData.put("BirthDt", lBirthDt);
		lexecuteFinacleScript_CustomData.put("Communication_AddrCategory", "");
		lexecuteFinacleScript_CustomData.put("TypeDesc", "");
		lexecuteFinacleScript_CustomData.put("Permanent_Telex", "");
		lexecuteFinacleScript_CustomData.put("Communication_HoldMailFlag", "");
		lexecuteFinacleScript_CustomData.put("Communication_FaxNum", "");
		lexecuteFinacleScript_CustomData.put("Communication_Country", lcommunicationaddrcountryCode);
		lexecuteFinacleScript_CustomData.put("Permanent_HoldMailFlag", "");
		lexecuteFinacleScript_CustomData.put("BirthYear", lBirthYear);
		lexecuteFinacleScript_CustomData.put("RelationshipManager", "");
		lexecuteFinacleScript_CustomData.put("DocCode", "");
		lexecuteFinacleScript_CustomData.put("Permanent_Country", "");
		lexecuteFinacleScript_CustomData.put("IDIssuedOrganisation", "");
		lexecuteFinacleScript_CustomData.put("Permanent_PrefAddr", "");
		lexecuteFinacleScript_CustomData.put("Permanent_PrefFormat", "");
		lexecuteFinacleScript_CustomData.put("TypeCode", "");
		lexecuteFinacleScript_CustomData.put("Communication_FreeTextLabel", ".");
		lexecuteFinacleScript_CustomData.put("EmploymentStatus", "");
		lexecuteFinacleScript_CustomData.put("Communication_PrefAddr", "");
		lexecuteFinacleScript_CustomData.put("Permanent_FreeTextLabel", ".");
		lexecuteFinacleScript_CustomData.put("ReferenceNum", "");
		lexecuteFinacleScript_CustomData.put("PrimarySolId", "");
		lexecuteFinacleScript_CustomData.put("Communication_Telex", "");
		lexecuteFinacleScript_CustomData.put("IssueDt", "");
		lexecuteFinacleScript_CustomData.put("PlaceOfIssue", "");
		lexecuteFinacleScript_CustomData.put("Permanent_FaxNum", "");
		lexecuteFinacleScript_CustomData.put("Permanent_StartDt", "");
		lexecuteFinacleScript_CustomData.put("Communication_StartDt", "");
		lexecuteFinacleScript_CustomData.put("EntityType", "");
		lexecuteFinacleScript_CustomData.put("SelectProduct", lAccountInformationProductName);
		lexecuteFinacleScript_CustomData.put("AccountStatementFlag", lAccountInformationAccountStatement);
		lexecuteFinacleScript_CustomData.put("ModeOfDelivery", lAccountInformationModeOfDelivery);
		lexecuteFinacleScript_CustomData.put("ModeOfOperation", lAccountInformationModeOfOperation);
		lexecuteFinacleScript_CustomData.put("operInstructions", lAccountInformationOperatinginstructions);
		lexecuteFinacleScript_CustomData.put("PanRemarks", lcommunicationaddrPanRemarks);
		lexecuteFinacleScript_CustomData.put("PanAcknowledgementNumber", lcommunicationaddrPanAcknowledgementNumber);
		lexecuteFinacleScript_CustomData.put("NonAgriculturalIncome", lcommunicationaddrNonAgriculturalIncome);
		lexecuteFinacleScript_CustomData.put("AgriculturalIncome", lcommunicationaddrAgriculturalIncome);
		lexecuteFinacleScript_CustomData.put("guardianDistrict", lguardianDistrict);
		lexecuteFinacleScript_CustomData.put("Relationship", lRelationship);
		lexecuteFinacleScript_CustomData.put("NomineeAadhaar", lNomineeAadhaar);
		lexecuteFinacleScript_CustomData.put("LinkAadhaar", lLinkAadhaar);
		lexecuteFinacleScript_CustomData.put("LinkAadhaar", lLinkAadhaar);
		lexecuteFinacleScript_CustomData.put("SMSBanking", lSMSBanking);
		lexecuteFinacleScript_CustomData.put("POSB", lPOSB);
		lexecuteFinacleScript_CustomData.put("MissedCallBanking", lMissedCallBanking);
		lexecuteFinacleScript_CustomData.put("DeclarationCheck", lDeclarationCheck);
		lexecuteFinacleScript_CustomData.put("welcomeKitProvided", lwelcomeKitProvided);
		lexecuteFinacleScript_CustomData.put("Signature", lSignature);
		lexecuteFinacleScript_CustomData.put("Investment", lInvestment);
		lexecuteFinacleScript_CustomData.put("nomName", lNomineeDetsname);

		if (lNomineeDetsDOB.isEmpty() || lNomineeDetsDOB.equals("")) {
			lexecuteFinacleScript_CustomData.put("NomineeDOB", "");
		} else {
			lexecuteFinacleScript_CustomData.put("NomineeDOB", sdf.format(date1));// check
		}
		lexecuteFinacleScript_CustomData.put("NomineeRealationship", lNomineeRelationship);
		lexecuteFinacleScript_CustomData.put("NomineeAddrLine1", lNomineeDetsaddr1);
		lexecuteFinacleScript_CustomData.put("NomineeAddrLine2", lNomineeDetsaddr2);
		lexecuteFinacleScript_CustomData.put("City_Town_Village", lNomineeCity);
		lexecuteFinacleScript_CustomData.put("district", lNomineeDistrict);
		lexecuteFinacleScript_CustomData.put("state", lNomineeState);
		lexecuteFinacleScript_CustomData.put("PinCode", lNomineeDetspincode);
		lexecuteFinacleScript_CustomData.put("NomineeMinorFlg", lNomineeisMinor);
		lexecuteFinacleScript_CustomData.put("welcomeKit", lAccountInformationwelcomeKitId);
		lexecuteFinacleScript_CustomData.put("sweepOutBk", lAccountInformationSweepin_Bank);
		lexecuteFinacleScript_CustomData.put("sweepOutBr", lAccountInformationSweepin_Branch);
		lexecuteFinacleScript_CustomData.put("sweepOutAcctNo", lAccountInformationSweepin_Account_Number);
		lexecuteFinacleScript_CustomData.put("doorStpFlg", lAccountInformationDoorStepBanking);
		lexecuteFinacleScript_CustomData.put("IBEnrolFlg", lAccountInformationIBEnrollment);
		lexecuteFinacleScript_CustomData.put("MBEnrolFlg", lAccountInformationMBEnrollment);
		lexecuteFinacleScript_CustomData.put("IVREnrolFlg", lAccountInformationIVREnrollment);
		lexecuteFinacleScript_CustomData.put("backValueDt", "");
		lexecuteFinacleScript_CustomData.put("initDepAmt", lAccountInformationInitial_Dep_Amount);
		lexecuteFinacleScript_CustomData.put("solId", "");
		lexecuteFinacleScript_CustomData.put("schmCode", "");
		lexecuteFinacleScript_CustomData.put("crncyCode", "");
		lexecuteFinacleScript_CustomData.put("contextBankId", "");
		lexecuteFinacleScript_CustomData.put("intCrAcctFlg", "");
		lexecuteFinacleScript_CustomData.put("intDrAcctFlg", "");
		lexecuteFinacleScript_CustomData.put("nomAvailableFlg", "");
		lexecuteFinacleScript_CustomData.put("nomSrlNum", "");
		lexecuteFinacleScript_CustomData.put("nomRegNum", "");
		lexecuteFinacleScript_CustomData.put("prefNomName", lNomineeDetsname);
		lexecuteFinacleScript_CustomData.put("address3", "");
		lexecuteFinacleScript_CustomData.put("cntryCode", lNomineeCountryCode);
		lexecuteFinacleScript_CustomData.put("nomPcnt", "");
		lexecuteFinacleScript_CustomData.put("nomGuardName", lNomineeDetsguardian_name);
		lexecuteFinacleScript_CustomData.put("minorGuardCode", "");
		lexecuteFinacleScript_CustomData.put("guardAddress1", lguardian_addr1);
		lexecuteFinacleScript_CustomData.put("guardAddress2", lguardian_addr2);
		lexecuteFinacleScript_CustomData.put("guardAddress3", "");
		lexecuteFinacleScript_CustomData.put("guardCityCode", lguardian_city);
		lexecuteFinacleScript_CustomData.put("guardStateCode", lguardian_state);
		lexecuteFinacleScript_CustomData.put("guardCntryCode", lNomineeCountryCode);
		lexecuteFinacleScript_CustomData.put("guardPostalCode", lguardian_pincode);
		lexecuteFinacleScript_CustomData.put("ModeOfPayment", lAdditionalInfoModeOfPayment);
		lexecuteFinacleScript_CustomData.put("debitAcctId", "");
		lexecuteFinacleScript_CustomData.put("POSBACCOUNTNO", lTypeDesc);
		lexecuteFinacleScript_CustomData.put("AGENTID", lRelationshipManager);
		lexecuteFinacleScript_CustomData.put("AUTHREFNO", lReferenceNum);
		lexecuteFinacleScript_CustomData.put("POSB_cif", lAdditionalInfoPOSBCIFNumber);
		lexecuteFinacleScript_CustomData.put("Aadhaar_ConfId", lAdditionalInfoAadhaarConfId);
		lexecuteFinacleScript_CustomData.put("Facility_Id", lAdditionalInfoFacilityId);
		lexecuteFinacleScript_CustomData.put("IIN", lIIN);
		lexecuteFinacleScript_CustomData.put("EXTCUST", lEXTCUST);
		lexecuteFinacleScript_CustomData.put("PAN_NAME", lPAN_NAME);
		lexecuteFinacleScript_CustomData.put("PanMatchFlg", lPanMatchFlg);
		lexecuteFinacleScript_CustomData.put("PanMatchPercentage", lPanMatchPercentage);

		
		lreqId.put("requestId", lReqId);
		lexecuteFinacleScriptRequest.put("ExecuteFinacleScriptInputVO", lreqId);
		lexecuteFinacleScriptRequest.put("executeFinacleScript_CustomData", lexecuteFinacleScript_CustomData);
		lBody.put("executeFinacleScriptRequest", lexecuteFinacleScriptRequest);
		lreqHeader.put("RequestHeader", lReqHeader);
		lJsonObj.put("schemaLocation", lSchemaLocation);
		lJsonObj.put("Header", lreqHeader);
		lJsonObj.put("Body", lBody);
		lFinalReq.put("FIXML", lJsonObj);

		LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " AccountOpeningServiceExt createServiceReq" + lFinalReq);

		try {

			String lmiddlename = "";
			String lastname = "";
			String custID = "";
			String PERM_ADDR_LINE3 = "";
			String PERM_ADDR_LINE4 = "";
			String RESIDENCE_STATUS = "";

			LOG.debug("lrefno generated is ::" + lrefno);

			boolean resultcustInfo = insertTbDbtpCustInformation(lrefno, ltitle, lname, lmiddlename, lastname,
					lmaiden_name, custID, lDateDOB, lAdditionalInfoplace_birth, lNomineeisMinor,
					lAdditionalInforeligion, lGender, lmobile, lresidence, lemail, lPermanent_AddrLine1,
					lPermanent_AddrLine2, PERM_ADDR_LINE3, PERM_ADDR_LINE4, lcity, ldistrict, lstate, lpincode, laddr1,
					laddr2, llcommunicationaddrcity, llcommunicationaddrDistrict, lcommunicationaddrstate,
					lcommunicationaddrpincodelocation, lAdditionalInfonationality, RESIDENCE_STATUS,
					lAdditionalInfomarital_status, lAdditionalInfooccupation_type, lAdditionalInfogross_annual_income,
					lAdditionalInfoeducation_level, lpan, lAdditionalInfoPassportNumber, lAdditionalInfoDrivingLicence,
					lAdditionalInfoVoterId, lform60_61, lfather_name, lmother_name, lTypeDesc);

			LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS
					+ " AccountOpeningServiceExt insertTbDbtpCustInformation status" + resultcustInfo);

			String EXTERNAL_ACCOUNT_NO = "";
			String lNOMINEE_ADDR_LINE_3 = "";
			String lNOMINEE_ADDR_LINE_4 = "";
			String lINTERESTED_IN_INVESTMENT = "";
			String lDECLARATION = "";
			String lDEDUPE_CHECK_REQD = "";
			String lAML_CHECK_REQD = "";
			String lRISK_PROFILING_REQD = "";
			String lRISK_CATEGORY = "";
			String lDATA_CAPTURE_MODE = "";
			String lONLINE_SYNC_STATUS = "";
			String lMAKER_ID = "";
			String lMAKER_TS = "";
			String lCHECKER_ID = "";
			String lCHECKER_TS = "";
			String lCUSTOM_FIELD1 = "";
			String lCUSTOM_FIELD2 = "";
			String lCUSTOM_FIELD3 = "";
			String lCUSTOM_FIELD4 = "";
			String lCUSTOM_FIELD5 = "";
			String lEXTERNAL_REF_NO = "";
			String lEXTERNAL_STATUS = "";
			String lEXTERNAL_ERROR = "";

			boolean resultAccountInfo = insertTbDbtpAccountInformation(lrefno, EXTERNAL_ACCOUNT_NO,
					lAccountInformationProductName, lAccountInformationAccountStatement,
					lAccountInformationModeOfDelivery, lNomineeDetsname, date1, lNomineeRelationship, lNomineeDetsaddr1,
					lNomineeDetsaddr2, lNOMINEE_ADDR_LINE_3, lNOMINEE_ADDR_LINE_4, lNomineeCity, lNomineeDistrict,
					lNomineeState, lNomineeDetspincode, lAccountInformationSweepin_Bank,
					lAccountInformationSweepin_Account_Number, lAccountInformationSweepin_Branch,
					lAccountInformationIBEnrollment, lAccountInformationMBEnrollment, lAccountInformationIVREnrollment,
					lAccountInformationInitial_Dep_Amount, lAdditionalInfoModeOfPayment, lINTERESTED_IN_INVESTMENT,
					lDECLARATION, lAccountInformationwelcomeKitId, lDEDUPE_CHECK_REQD, lAML_CHECK_REQD,
					lRISK_PROFILING_REQD, lRISK_CATEGORY, lDATA_CAPTURE_MODE, lONLINE_SYNC_STATUS, lMAKER_ID, lMAKER_TS,
					lCHECKER_ID, lCHECKER_TS, lCUSTOM_FIELD1, lCUSTOM_FIELD2, lCUSTOM_FIELD3, lCUSTOM_FIELD4,
					lCUSTOM_FIELD5, lEXTERNAL_REF_NO, lEXTERNAL_STATUS, lEXTERNAL_ERROR, lTypeDesc);

			LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS
					+ " AccountOpeningServiceExt insertTbDbtpAccountInformation status" + resultAccountInfo);

		} catch (Exception ex) {
			LOG.error("Exception Occured ", ex);
			/*LOG.debug("[FrameworkServices] => Exception  ...." + ex.getMessage());
			LOG.debug("[FrameworkServices] => Exception  ...." + ex.getLocalizedMessage());
			LOG.debug("[FrameworkServices] => Exception  ...." + ex.getStackTrace());*/
		}
		return lFinalReq;
	}

	private boolean insertTbDbtpAccountInformation(String lrefno, String eXTERNAL_ACCOUNT_NO, String pRODUCT_TYPE,
			String sTATEMENT_REQD, String sTATEMENT_DELIVER_MODE, String lNomineeDetsname, Date lNomineeDetsDOB,
			String nOMINEE_RELATIONSHIP, String lNomineeDetsaddr1, String lNomineeDetsaddr2, String nOMINEE_ADDR_LINE_3,
			String nOMINEE_ADDR_LINE_4, String nOMINEE_CITY_TOWN, String nOMINEE_DISTRICT, String nOMINEE_STATE,
			BigDecimal lNomineeDetspincode, String lAccountInformationSweepin_Bank,
			String lAccountInformationSweepin_Account_Number, String lAccountInformationSweepin_Branch, String iB_REQD,
			String mB_REQD, String iVR_REQD, String iNITIAL_DEPOSIT_AMT, String pAYMENT_MODE,
			String iNTERESTED_IN_INVESTMENT, String dECLARATION, String lAccountInformationwelcomeKitId,
			String dEDUPE_CHECK_REQD, String aML_CHECK_REQD, String rISK_PROFILING_REQD, String rISK_CATEGORY,
			String dATA_CAPTURE_MODE, String oNLINE_SYNC_STATUS, String mAKER_ID, String mAKER_TS, String cHECKER_ID,
			String cHECKER_TS, String cUSTOM_FIELD1, String cUSTOM_FIELD2, String cUSTOM_FIELD3, String cUSTOM_FIELD4,
			String cUSTOM_FIELD5, String eXTERNAL_REF_NO, String eXTERNAL_STATUS, String eXTERNAL_ERROR,
			String lTypeDesc) {
		LOG.debug("Inside TbDbtpAccountInformation()-->1");

		TbDbtpAccountInformation tbDbtpAccountInformation = new TbDbtpAccountInformation();
		boolean lReturnStatus = true;
		Utils lUtils = new Utils();


		tbDbtpAccountInformation.setTxnRefNo(lrefno);
		tbDbtpAccountInformation.setExternalAccountNo(eXTERNAL_ACCOUNT_NO);
		tbDbtpAccountInformation.setProductType(pRODUCT_TYPE);
		tbDbtpAccountInformation.setStatementReqd(sTATEMENT_REQD);
		tbDbtpAccountInformation.setStatementDeliverMode(sTATEMENT_DELIVER_MODE);
		tbDbtpAccountInformation.setNomineeName(lNomineeDetsname);
		tbDbtpAccountInformation.setNomineeDob(lNomineeDetsDOB);
		tbDbtpAccountInformation.setNomineeRelationship(nOMINEE_RELATIONSHIP);
		tbDbtpAccountInformation.setNomineeAddrLine1(lNomineeDetsaddr1);
		tbDbtpAccountInformation.setNomineeAddrLine2(lNomineeDetsaddr2);
		tbDbtpAccountInformation.setNomineeAddrLine3(nOMINEE_ADDR_LINE_3);
		tbDbtpAccountInformation.setNomineeAddrLine4(nOMINEE_ADDR_LINE_4);
		tbDbtpAccountInformation.setNomineeCityTown(nOMINEE_CITY_TOWN);
		tbDbtpAccountInformation.setNomineeDistrict(nOMINEE_DISTRICT);
		tbDbtpAccountInformation.setNomineeState(nOMINEE_STATE);
		tbDbtpAccountInformation.setNomineePincode(lNomineeDetspincode);
		tbDbtpAccountInformation.setSweepOutBank(lAccountInformationSweepin_Bank);
		tbDbtpAccountInformation.setSweepOutBranch(lAccountInformationSweepin_Branch);
		tbDbtpAccountInformation.setSweepOutAccountNo(lAccountInformationSweepin_Account_Number);
		tbDbtpAccountInformation.setIbReqd(iB_REQD);
		tbDbtpAccountInformation.setMbReqd(mB_REQD);
		tbDbtpAccountInformation.setIvrReqd(iVR_REQD);
		tbDbtpAccountInformation.setWelcomeKit(lAccountInformationwelcomeKitId);
		tbDbtpAccountInformation.setDedupeCheckReqd(dEDUPE_CHECK_REQD);
		tbDbtpAccountInformation.setAmlCheckReqd(aML_CHECK_REQD);
		tbDbtpAccountInformation.setRiskProfilingReqd(rISK_PROFILING_REQD);
		tbDbtpAccountInformation.setRiskCategory(rISK_CATEGORY);
		tbDbtpAccountInformation.setDataCaptureMode(dATA_CAPTURE_MODE);
		tbDbtpAccountInformation.setOnlineSyncStatus(oNLINE_SYNC_STATUS);
		tbDbtpAccountInformation.setMakerId(mAKER_ID);
		tbDbtpAccountInformation.setMakerTs(new Timestamp(new Date().getTime()));
		tbDbtpAccountInformation.setCheckerId(cHECKER_ID);
		tbDbtpAccountInformation.setCheckerTs(new Timestamp(new Date().getTime()));
		tbDbtpAccountInformation.setCustomField1(cUSTOM_FIELD1);
		tbDbtpAccountInformation.setCustomField2(cUSTOM_FIELD2);
		tbDbtpAccountInformation.setCustomField3(cUSTOM_FIELD3);
		tbDbtpAccountInformation.setCustomField4(cUSTOM_FIELD4);
		tbDbtpAccountInformation.setCustomField5(cUSTOM_FIELD5);
		tbDbtpAccountInformation.setExternalRefNo(eXTERNAL_REF_NO);
		tbDbtpAccountInformation.setExternalStatus(eXTERNAL_STATUS);
		tbDbtpAccountInformation.setExternalError(eXTERNAL_ERROR);
		tbDbtpAccountInformation.setPosbaccountno(lTypeDesc);
		lReturnStatus = lUtils.PostTxn(tbDbtpAccountInformation, "New");

		LOG.debug("Status After Inserting Into TbDbtpAccountInformation" + lReturnStatus);
		LOG.debug("Exiting  TbDbtpAccountInformation()-->2");
		return false;
	}

	private boolean insertTbDbtpCustInformation(String lrefno, String title, String lname, String lmiddlename,
			String lastname, String lmaiden_name, String custUID, Date lDateDOB, String lAdditionalInfoplace_birth,
			String iS_MINOR, String lAdditionalInforeligion, String lGender, String lmobile, String lresidence,
			String lemail, String pERM_ADDR_LINE1, String pERM_ADDR_LINE2, String pERM_ADDR_LINE3,
			String pERM_ADDR_LINE4, String lcity, String ldistrict, String lstate, BigDecimal lpincode, String laddr1,
			String laddr2, String llcommunicationaddrcity, String cOMM_DISTRICT, String lcommunicationaddrstate,
			BigDecimal lcommunicationaddrpincodelocation, String lAdditionalInfonationality, String rESIDENCE_STATUS,
			String lAdditionalInfomarital_status, String lAdditionalInfooccupation_type,
			String lAdditionalInfogross_annual_income, String lAdditionalInfoeducation_level, String lpan,
			String pASSPORT_NO, String dRIVING_LICENCE_NO, String vOTER_ID, String fORM_60, String lfather_name,
			String lmother_name, String lTypeDesc) {
		LOG.debug("Inside insertTbDbtpCustInformation()-->1");
		boolean lReturnStatus = true;

		TbDbtpCustInformation tbDbtpCustInformation = new TbDbtpCustInformation();
		Utils lUtils = new Utils();
		LOG.debug("Setting values into TbDbtpCustInformation table");
		tbDbtpCustInformation.setTxnRefNo(lrefno);
		tbDbtpCustInformation.setTitle(title);
		tbDbtpCustInformation.setLastName(lastname);
		tbDbtpCustInformation.setMiddleName(lmiddlename);
		tbDbtpCustInformation.setMaidenName(lmaiden_name);
		tbDbtpCustInformation.setCustUid(custUID);
		tbDbtpCustInformation.setDob(lDateDOB);
		tbDbtpCustInformation.setPlaceOfBirth(lAdditionalInfoplace_birth);
		tbDbtpCustInformation.setIsMinor(iS_MINOR);
		tbDbtpCustInformation.setReligion(lAdditionalInforeligion);
		tbDbtpCustInformation.setGender(lGender);
		tbDbtpCustInformation.setMobileNo(lmobile);
		tbDbtpCustInformation.setResidencePhone(lresidence);
		tbDbtpCustInformation.setEmailId(lemail);
		tbDbtpCustInformation.setPermAddrLine1(pERM_ADDR_LINE1);
		tbDbtpCustInformation.setPermAddrLine2(pERM_ADDR_LINE2);
		tbDbtpCustInformation.setPermAddrLine3(pERM_ADDR_LINE3);
		tbDbtpCustInformation.setPermAddrLine4(pERM_ADDR_LINE4);
		tbDbtpCustInformation.setCityTown(lcity);
		tbDbtpCustInformation.setDistrict(ldistrict);
		tbDbtpCustInformation.setState(lstate);
		tbDbtpCustInformation.setPincode(lpincode);
		tbDbtpCustInformation.setCommAddrLine1(laddr1);
		tbDbtpCustInformation.setCommAddrLine2(laddr2);
		tbDbtpCustInformation.setCommCityTown(llcommunicationaddrcity);
		tbDbtpCustInformation.setCommDistrict(cOMM_DISTRICT);
		tbDbtpCustInformation.setCommState(lcommunicationaddrstate);
		tbDbtpCustInformation.setCommPincode(lcommunicationaddrpincodelocation);
		tbDbtpCustInformation.setNationality(lAdditionalInfonationality);
		tbDbtpCustInformation.setResidenceStatus(rESIDENCE_STATUS);
		tbDbtpCustInformation.setMaritalStatus(lAdditionalInfomarital_status);
		tbDbtpCustInformation.setOccupationType(lAdditionalInfooccupation_type);
		tbDbtpCustInformation.setGrossAnnualIncome(lAdditionalInfogross_annual_income);
		tbDbtpCustInformation.setEducationLevel(lAdditionalInfoeducation_level);
		tbDbtpCustInformation.setPanNo(lpan);
		tbDbtpCustInformation.setPassportNo(pASSPORT_NO);
		tbDbtpCustInformation.setVoterId(vOTER_ID);
		tbDbtpCustInformation.setDrivingLicenceNo(dRIVING_LICENCE_NO);
		tbDbtpCustInformation.setVoterId(vOTER_ID);
		tbDbtpCustInformation.setForm60(fORM_60);
		tbDbtpCustInformation.setFatherSpouseName(lfather_name);
		tbDbtpCustInformation.setMotherName(lmother_name);
		tbDbtpCustInformation.setCheckerTs(new Timestamp(new Date().getTime()));
		tbDbtpCustInformation.setMakerTs(new Timestamp(new Date().getTime()));
		lReturnStatus = lUtils.PostTxn(tbDbtpCustInformation, "New");
		LOG.debug("Status After Inserting Into CustomerInformation" + lReturnStatus);

		LOG.debug("Exiting  insertTbDbtpCustInformation()-->2");
		return lReturnStatus;
	}
	
	public String getIINCode(String lIINname) {
		LOG.debug("Iniside  getIINCode()....");
		LOG.debug("IINname -->"+lIINname);

		String lIINCode = null;
		EntityManager gEntityManager = null;
		try {
			Utils lUtils = new Utils();
			gEntityManager = lUtils.getEntityManager();
			LOG.debug("EntityManager object created..");
			LOG.info(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "Entering AccountOpeningServiceExt  getIINCode()-->1");
			String query = "SELECT * FROM TB_DBTP_IIN_CODE WHERE IIN_NAME=?1";
			Query ltbDbtpIINCode = gEntityManager.createNativeQuery(query, TbDbtpIinCode.class);
			LOG.debug("Creating NativeQuery");
			LOG.info(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "Entering AccountOpeningServiceExt  getIINCode()-->2");
			ltbDbtpIINCode.setParameter(1, lIINname);
			List<TbDbtpIinCode> ltbDbtpIINCodeRes =ltbDbtpIINCode.getResultList();
			LOG.debug("DB result size is :"+ltbDbtpIINCodeRes.size());
			lIINCode = ltbDbtpIINCodeRes.get(0).getIinCode();
			LOG.debug("IIN Code is-->: "+lIINCode);
			LOG.info(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + "Entering AccountOpeningServiceExt  getIINCode()-->3");
		} catch (Exception ex) {
			LOG.error("Exception Occured ", ex);
			/*LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getMessage());
			LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getLocalizedMessage());
			LOG.debug(ServerConstants.LOGGER_PREFIX_FRAMEWORKS + " Exception  ...." + ex.getStackTrace());*/
		} finally {
			if (gEntityManager != null) {
				gEntityManager.close();
				LOG.debug("Closed Entity Manager..");
			}
		}
		return lIINCode;

	}

	public String getrandomNumber() {
		Random lRandom = new Random();
		int lRandomNo = lRandom.nextInt(1000000);
		String randomNumber = Integer.toString(lRandomNo);
		return randomNumber;
	}
}
