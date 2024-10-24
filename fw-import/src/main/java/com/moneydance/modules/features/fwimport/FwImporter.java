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
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

/**
 * Data record to hold an imported row.
 *
 * @param accountNumber Account name or number
 * @param ticker        Ticker symbol
 * @param securityName  Security name or description
 * @param shares        Quantity of shares
 * @param price         Price
 * @param balance       Balance or value
 * @param effectiveDate Effective date
 */
record RowRec(
	String accountNumber,
	String ticker,
	String securityName,
	BigDecimal shares,
	BigDecimal price,
	BigDecimal balance,
	LocalDate effectiveDate) {

} // end record RowRec

/**
 * Module used to import Fidelity NetBenefits workplace account data into
 * Moneydance.
 */
public class FwImporter extends CsvProcessor {
	private final Account root;
	private final CurrencyTable securities;

	private final LinkedHashMap<CurrencyType, SecurityHandler> priceChanges = new LinkedHashMap<>();
	private final LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
	private ResourceBundle msgBundle = null;

	private static final String propertiesFileName = "fw-import.properties";
	private static final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("E MMM d, y");

	/**
	 * Sole constructor.
	 *
	 * @param importWindow Our import console
	 * @param accountBook  Moneydance account book
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
		// Importing price data from file %s.
		writeFormatted("FWIMP01", this.importWindow.getFileToImport().getFileName());

		processFile();
		// Found effective date%s %s.
		writeFormatted("FWIMP09", this.dates.size() == 1 ? "" : "s",
			this.dates.stream().map(dt -> dt.format(dateFmt)).collect(Collectors.joining("; ")));

		if (!isModified()) {
			// No new price data found.
			writeFormatted("FWIMP08");
		}

	} // end importFile()

	/**
	 * Retrieve data from columns in current row
	 * @return Populated RowRec instance
	 */
	private RowRec importRow() throws MduException {
		return new RowRec(
			getCol("col.account.num"),
			getCol("col.ticker"),
			getCol("col.name"),
			new BigDecimal(getCol("col.shares")),
			new BigDecimal(getCol("col.price")),
			new BigDecimal(getCol("col.value")),
			LocalDate.parse(getCol("col.date")));
	} // end importRow()

	/**
	 * Import this row of the comma separated value file.
	 */
	protected void processRow() throws MduException {
		RowRec imp = importRow();
		Optional<Account> account =
			MdUtil.getSubAccountByInvestNumber(this.root, imp.accountNumber());

		if (account.isEmpty()) {
			// Unable to obtain Moneydance investment account with number [%s].
			writeFormatted("FWIMP05", imp.accountNumber());
		}
		CurrencyType security = this.securities.getCurrencyByTickerSymbol(imp.ticker());

		if (security == null) {
            account.ifPresent(subAcct -> verifyAccountBalance(subAcct, imp));
		} else {
			storePriceQuoteIfDiff(security, imp.price(), imp.effectiveDate());

			account.ifPresent(subAcct -> verifyShareBalance(subAcct, security, imp.shares()));
		}
		this.dates.add(imp.effectiveDate());

	} // end processRow()

	/**
	 * @param security      The Moneydance security to use
	 * @param price         Price found during import
	 * @param effectiveDate Effective date for quote
	 */
	private void storePriceQuoteIfDiff(CurrencyType security, BigDecimal price,
									   LocalDate effectiveDate) {
		int effDateInt = MdUtil.convLocalToDateInt(effectiveDate);
		SnapshotList ssList = new SnapshotList(security);
		Optional<CurrencySnapshot> snapshot = ssList.getSnapshotForDate(effDateInt);
		BigDecimal oldPrice = snapshot.map(ss ->
				MdUtil.getAndValidateCurrentSnapshotPrice(security, ss, this.locale,
						correction -> writeFormatted("FWIMP00", correction)))
				.orElse(BigDecimal.ONE);

		// store this quote if it differs and we don't already have this security
		if ((snapshot.isEmpty() || effDateInt != snapshot.get().getDateInt()
				|| price.compareTo(oldPrice) != 0) && !this.priceChanges.containsKey(security)) {
			// Change %s (%s) price from %s to %s (<span class="%s">%+.2f%%</span>).
			NumberFormat priceFmt = MdUtil.getCurrencyFormat(this.locale, oldPrice, price);
			double newPrice = price.doubleValue();
			writeFormatted("FWIMP03", security.getName(), security.getTickerSymbol(),
				priceFmt.format(oldPrice), priceFmt.format(newPrice),
				HTMLPane.getSpanCl(price, oldPrice), (newPrice / oldPrice.doubleValue() - 1) * 100);

			addHandler(new SecurityHandler(ssList).storeNewPrice(newPrice, effDateInt));
		}

	} // end storePriceQuoteIfDiff(CurrencyType, BigDecimal, LocalDate)

	/**
	 * @param account Moneydance account
	 * @param imp     Imported record from current row
	 */
	private void verifyAccountBalance(Account account, RowRec imp) {
		BigDecimal balance = MdUtil.getCurrentBalance(account);

		if (imp.balance().compareTo(balance) != 0) {
			// Found a different balance in account %s: have %s, imported %s;
			// Note: No Moneydance security for ticker symbol [%s] (%s)
			NumberFormat cf = MdUtil.getCurrencyFormat(this.locale, balance, imp.balance());
			writeFormatted("FWIMP02", account.getAccountName(), cf.format(balance),
				cf.format(imp.balance()), imp.ticker(), imp.securityName());
		}

	} // end verifyAccountBalance(Account, RowRec)

	/**
	 * @param account Moneydance account
	 * @param security The Moneydance security to use
	 * @param importedShares Shares found during import
	 */
	private void verifyShareBalance(Account account, CurrencyType security,
									BigDecimal importedShares) {
		MdUtil.getSubAccountByName(account, security.getName()).ifPresentOrElse(secAccount -> {
			BigDecimal balance = MdUtil.getCurrentBalance(secAccount);

			if (importedShares.compareTo(balance) != 0) {
				// Found a different %s (%s) share balance in account %s: have %s, imported %s.
				NumberFormat nf = MdUtil.getNumberFormat(this.locale, balance, importedShares);
				writeFormatted("FWIMP04", secAccount.getAccountName(), security.getTickerSymbol(),
					account.getAccountName(), nf.format(balance), nf.format(importedShares));
			}
		}, () -> {
			// Unable to obtain Moneydance security [%s (%s)] in account %s.
			writeFormatted("FWIMP06", security.getName(), security.getTickerSymbol(),
				account.getAccountName());
		});

	} // end verifyShareBalance(Account, CurrencyType, BigDecimal)

	/**
	 * Add a security handler to our collection.
	 *
	 * @param handler A deferred update security handler to store
	 */
	private void addHandler(SecurityHandler handler) {
		this.priceChanges.put(handler.getSecurity(), handler);

	} // end addHandler(SecurityHandler)

	/**
	 * Commit any changes to Moneydance.
	 */
	public void commitChanges() {
		int numPricesSet = this.priceChanges.size();
		this.priceChanges.forEach((security, sHandler) -> sHandler.applyUpdate());

		// Changed %d security price%s.
		writeFormatted("FWIMP07", numPricesSet, numPricesSet == 1 ? "" : "s");

		forgetChanges();

	} // end commitChanges()

	/**
	 * Clear out any pending changes.
	 */
	public void forgetChanges() {
		this.priceChanges.clear();
		this.dates.clear();

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
