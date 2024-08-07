/*
 * Created on Dec 16, 2017
 */
package com.moneydance.modules.features.fwimport;

import com.infinitekind.moneydance.model.Account;
import com.infinitekind.moneydance.model.AccountBook;
import com.infinitekind.moneydance.model.CurrencySnapshot;
import com.infinitekind.moneydance.model.CurrencyTable;
import com.infinitekind.moneydance.model.CurrencyType;
import com.leastlogic.mdimport.util.CsvProcessor;
import com.leastlogic.moneydance.util.MdUtil;
import com.leastlogic.moneydance.util.MduException;
import com.leastlogic.moneydance.util.SecurityHandler;
import com.leastlogic.moneydance.util.SnapshotList;
import com.leastlogic.swing.util.HTMLPane;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.ResourceBundle;

import static java.math.RoundingMode.HALF_EVEN;
import static java.time.format.FormatStyle.MEDIUM;

/**
 * Module used to import Fidelity NetBenefits workplace account data into
 * Moneydance.
 */
public class FwImporter extends CsvProcessor {
	private Account root;
	private CurrencyTable securities;

	private LocalDate marketDate = null;
	private LinkedHashMap<CurrencyType, SecurityHandler> priceChanges = new LinkedHashMap<>();
	private int numPricesSet = 0;
	private ResourceBundle msgBundle = null;

	private static final String propertiesFileName = "fw-import.properties";
	private static final DateTimeFormatter dateFmt = DateTimeFormatter.ofLocalizedDate(MEDIUM);
	private static final int PRICE_FRACTION_DIGITS = 6;

	/**
	 * Sole constructor.
	 *
	 * @param importWindow
	 * @param accountBook Moneydance account book
	 */
	public FwImporter(FwImportWindow importWindow, AccountBook accountBook) {
		super(importWindow, propertiesFileName);
		this.root = accountBook.getRootAccount();
		this.securities = accountBook.getCurrencies();

	} // end (FwImportWindow, AccountBook) constructor

	/**
	 * Import the selected comma separated value file.
	 */
	public void importFile() throws MduException {
		this.marketDate = ((FwImportWindow) this.importWindow).getMarketDate();

		if (this.marketDate == null) {
			// Market date must be specified.
			writeFormatted("FWIMP00");
		} else {
			// Importing price data for %s from file %s.
			writeFormatted("FWIMP01", this.marketDate.format(dateFmt),
				this.importWindow.getFileToImport().getFileName());

			processFile();

			if (!isModified()) {
				// No new price data found in %s.
				writeFormatted("FWIMP08", this.importWindow.getFileToImport().getFileName());
			}
		}

	} // end importFile()

	/**
	 * Import this row of the comma separated value file.
	 */
	protected void processRow() throws MduException {
		Account account = MdUtil.getSubAccountByInvestNumber(this.root,
			getCol("col.account.num"));

		if (account == null) {
			// Unable to obtain Moneydance investment account with number [%s].
			writeFormatted("FWIMP05", getCol("col.account.num"));
		}
		CurrencyType security = this.securities.getCurrencyByTickerSymbol(getCol("col.ticker"));

		if (security == null) {
			verifyAccountBalance(account);
		} else {
			BigDecimal shares = new BigDecimal(getCol("col.shares"));

			storePriceQuoteIfDiff(security, shares);

			verifyShareBalance(account, security, shares);
		}

	} // end processRow()

	/**
	 * @param security The Moneydance security to use
	 * @param shares The number of shares included in the value
	 */
	private void storePriceQuoteIfDiff(CurrencyType security, BigDecimal shares)
			throws MduException {
		BigDecimal price = new BigDecimal(getCol("col.price"));
		BigDecimal value = new BigDecimal(getCol("col.value"));

		// see if shares * price = value to 2 places past the decimal point
		if (!shares.multiply(price).setScale(value.scale(), HALF_EVEN).equals(value)) {
			// no, so get the price to the sixth place past the decimal point
			price = value.divide(shares, PRICE_FRACTION_DIGITS, HALF_EVEN);
		}
		int importDate = MdUtil.convLocalToDateInt(this.marketDate);
		SnapshotList ssList = new SnapshotList(security);
		CurrencySnapshot snapshot = ssList.getSnapshotForDate(importDate);
		BigDecimal oldPrice = snapshot == null ? BigDecimal.ONE
				: MdUtil.convRateToPrice(snapshot.getRate());

		// store this quote if it differs and we don't already have this security
		if ((snapshot == null || importDate != snapshot.getDateInt()
				|| price.compareTo(oldPrice) != 0) && !this.priceChanges.containsKey(security)) {
			// Change %s (%s) price from %s to %s (<span class="%s">%+.2f%%</span>).
			NumberFormat priceFmt = MdUtil.getCurrencyFormat(this.locale, price);
			double newPrice = price.doubleValue();
			writeFormatted("FWIMP03", security.getName(), security.getTickerSymbol(),
				priceFmt.format(oldPrice), priceFmt.format(newPrice),
				HTMLPane.getSpanCl(price, oldPrice), (newPrice / oldPrice.doubleValue() - 1) * 100);

			addHandler(new SecurityHandler(ssList).storeNewPrice(newPrice, importDate));
			++this.numPricesSet;
		}

	} // end storePriceQuoteIfDiff(CurrencyType, BigDecimal)

	/**
	 * @param account Moneydance account
	 */
	private void verifyAccountBalance(Account account) throws MduException {
		if (account != null) {
			BigDecimal importedBalance = new BigDecimal(getCol("col.value"));
			BigDecimal balance = MdUtil.getCurrentBalance(account);

			if (importedBalance.compareTo(balance) != 0) {
				// Found a different balance in account %s: have %s, imported %s.
				// Note: No Moneydance security for ticker symbol [%s] (%s).
				NumberFormat cf = MdUtil.getCurrencyFormat(this.locale, importedBalance);
				writeFormatted("FWIMP02", account.getAccountName(), cf.format(balance),
					cf.format(importedBalance), getCol("col.ticker"), getCol("col.name"));
			}
		}

	} // end verifyAccountBalance(Account)

	/**
	 * @param account Moneydance account
	 * @param security
	 * @param importedShares
	 */
	private void verifyShareBalance(Account account, CurrencyType security,
			BigDecimal importedShares) {
		if (account != null) {
			Account secAccount = MdUtil.getSubAccountByName(account, security.getName());

			if (secAccount == null) {
				// Unable to obtain Moneydance security [%s (%s)] in account %s.
				writeFormatted("FWIMP06", security.getName(), security.getTickerSymbol(),
					account.getAccountName());
			} else {
				BigDecimal balance = MdUtil.getCurrentBalance(secAccount);

				if (importedShares.compareTo(balance) != 0) {
					// Found a different %s (%s) share balance in account %s: have %s, imported %s.
					NumberFormat nf = getNumberFormat(importedShares);
					writeFormatted("FWIMP04", secAccount.getAccountName(), security.getTickerSymbol(),
						account.getAccountName(), nf.format(balance), nf.format(importedShares));
				}
			}
		}

	} // end verifyShareBalance(Account, CurrencyType, BigDecimal)

	/**
	 * Add a security handler to our collection.
	 *
	 * @param handler
	 */
	private void addHandler(SecurityHandler handler) {
		this.priceChanges.put(handler.getSecurity(), handler);

	} // end addHandler(SecurityHandler)

	/**
	 * Commit any changes to Moneydance.
	 */
	public void commitChanges() {
		for (SecurityHandler sHandler : this.priceChanges.values()) {
			sHandler.applyUpdate();
		}
		// Changed %d security price%s.
		writeFormatted("FWIMP07", this.numPricesSet, this.numPricesSet == 1 ? "" : "s");

		forgetChanges();

	} // end commitChanges()

	/**
	 * Clear out any pending changes.
	 */
	public void forgetChanges() {
		this.priceChanges.clear();
		this.numPricesSet = 0;

	} // end forgetChanges()

	/**
	 * @return True when we have uncommitted changes in memory
	 */
	public boolean isModified() {

		return !this.priceChanges.isEmpty();
	} // end isModified()

	/**
	 * Release any resources we acquired.
	 *
	 * @return null
	 */
	public FwImporter releaseResources() {
		// nothing to release

		return null;
	} // end releaseResources()

	/**
	 * @return Our message bundle
	 */
	private ResourceBundle getMsgBundle() {
		if (this.msgBundle == null) {
			this.msgBundle = MdUtil.getMsgBundle(FwImportWindow.baseMessageBundleName,
				this.locale);
		}

		return this.msgBundle;
	} // end getMsgBundle()

	/**
	 * @param key The resource bundle key (or message)
	 * @return Message for this key
	 */
	private String retrieveMessage(String key) {
		try {

			return getMsgBundle().getString(key);
		} catch (Exception e) {
			// just use the key when not found
			return key;
		}
	} // end retrieveMessage(String)

	/**
	 * @param key The resource bundle key (or message)
	 * @param params Optional array of parameters for the message
	 */
	private void writeFormatted(String key, Object... params) {
		this.importWindow.addText(String.format(this.locale, retrieveMessage(key), params));

	} // end writeFormatted(String, Object...)

} // end class FwImporter
