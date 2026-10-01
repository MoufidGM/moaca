package com.cslsm.web.pages;

import com.cslsm.web.finance.FinanceModels.BankAccount;
import com.cslsm.web.finance.FinanceModels.BankBalance;
import com.cslsm.web.finance.FinanceModels.Movement;
import com.cslsm.web.finance.FinanceRepository;
import com.cslsm.web.reserves.MovementRepository;
import com.cslsm.web.reserves.ReserveService;
import com.cslsm.web.reserves.ReserveService.ReserveRuleException;
import com.cslsm.web.support.CurrentActor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Controller
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class ReservesController
{
	private static final int HISTORY_ROWS = 40;

	private final FinanceRepository finance;
	private final ReserveService reserves;
	private final CurrentActor currentActor;
	private final Clock clock;

	public ReservesController(FinanceRepository finance, ReserveService reserves, CurrentActor currentActor, Clock clock)
	{
		this.finance = finance;
		this.reserves = reserves;
		this.currentActor = currentActor;
		this.clock = clock;
	}

	@GetMapping("/reserves")
	public String reserves(Model model)
	{
		LocalDate today = LocalDate.now(clock);
		model.addAttribute("reception", finance.receptionBalance(today));
		model.addAttribute("safe", finance.safeBalance());
		model.addAttribute("restaurant", finance.restaurantBalance(today));
		model.addAttribute("sentToBankThisYear", finance.sentToBankSince(today.withDayOfYear(1)));
		model.addAttribute("year", today.getYear());
		model.addAttribute("today", today.toString());
		model.addAttribute("actions", ReserveService.Action.values());
		model.addAttribute("receptionMovements", finance.recentReceptionMovements(HISTORY_ROWS));
		model.addAttribute("safeMovements", finance.recentSafeMovements(HISTORY_ROWS));
		model.addAttribute("bankAccounts", BankAccount.values());
		model.addAttribute("bankActions", ReserveService.BankAction.values());
		Map<BankAccount, BankBalance> balances = new EnumMap<>(BankAccount.class);
		Map<BankAccount, List<Movement>> bankMovements = new EnumMap<>(BankAccount.class);
		for (BankAccount a : BankAccount.values())
		{
			balances.put(a, finance.bankBalance(a));
			bankMovements.put(a, finance.recentBankMovements(a, HISTORY_ROWS / 2));
		}
		model.addAttribute("bankBalances", balances);
		model.addAttribute("bankMovements", bankMovements);
		return "reserves";
	}

	@PostMapping("/reserves/bank")
	public String bank(@RequestParam String account, @RequestParam String action, @RequestParam(defaultValue = "") String date,
					   @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "") String note,
					   RedirectAttributes redirect)
	{
		String warning = reserves.recordBank(bankAccount(account), bankAction(action), date, amount, note, currentActor.require());
		redirect.addFlashAttribute(warning == null ? "flashOk" : "flashError", warning == null ? "Recorded." : warning);
		return "redirect:/reserves#bank";
	}

	@PostMapping("/reserves/bank/statement")
	public String bankStatement(@RequestParam String account, @RequestParam(defaultValue = "") String balance,
								@RequestParam(defaultValue = "") String note, RedirectAttributes redirect)
	{
		String info = reserves.setBankBalance(bankAccount(account), balance, note, currentActor.require());
		redirect.addFlashAttribute(info == null ? "flashOk" : "flashInfo", info == null ? "Account corrected to the statement balance." : info);
		return "redirect:/reserves#bank";
	}

	@PostMapping("/reserves/bank/{id}/delete")
	public String deleteBank(@PathVariable long id, RedirectAttributes redirect)
	{
		reserves.deleteBank(id, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Movement deleted.");
		return "redirect:/reserves#bank";
	}

	private static BankAccount bankAccount(String text)
	{
		try
		{
			return BankAccount.valueOf(text);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw new ReserveRuleException("Choose the account.");
		}
	}

	private static ReserveService.BankAction bankAction(String text)
	{
		try
		{
			return ReserveService.BankAction.valueOf(text);
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw new ReserveRuleException("Choose what you are recording.");
		}
	}

	@PostMapping("/reserves/movements")
	public String record(@RequestParam String action, @RequestParam(defaultValue = "") String date,
						 @RequestParam(defaultValue = "") String amount, @RequestParam(defaultValue = "") String note,
						 RedirectAttributes redirect)
	{
		ReserveService.Action a;
		try
		{
			a = ReserveService.Action.valueOf(action);
		}
		catch (IllegalArgumentException e)
		{
			throw new ReserveRuleException("Choose what you are recording.");
		}
		String warning = reserves.record(a, date, amount, note, currentActor.require());
		redirect.addFlashAttribute(warning == null ? "flashOk" : "flashError", warning == null ? "Recorded." : warning);
		return "redirect:/reserves";
	}

	@PostMapping("/reserves/count")
	public String count(@RequestParam String place, @RequestParam(defaultValue = "") String counted,
						@RequestParam(defaultValue = "") String note, RedirectAttributes redirect)
	{
		ReserveService.Place p;
		try
		{
			p = ReserveService.Place.valueOf(place);
		}
		catch (IllegalArgumentException e)
		{
			throw new ReserveRuleException("Choose the reception or the safe.");
		}
		String info = reserves.setBalance(p, counted, note, currentActor.require());
		redirect.addFlashAttribute(info == null ? "flashOk" : "flashInfo", info == null ? "Balance corrected to the amount counted." : info);
		return "redirect:/reserves";
	}

	@PostMapping("/reserves/movements/{table}/{id}/delete")
	public String delete(@PathVariable String table, @PathVariable long id, RedirectAttributes redirect)
	{
		MovementRepository.Table t;
		try
		{
			t = MovementRepository.Table.valueOf(table);
		}
		catch (IllegalArgumentException e)
		{
			throw new ReserveRuleException("Unknown movement.");
		}
		reserves.delete(t, id, currentActor.require());
		redirect.addFlashAttribute("flashOk", "Movement deleted.");
		return "redirect:/reserves";
	}

	@ExceptionHandler(ReserveRuleException.class)
	public String rule(ReserveRuleException e, RedirectAttributes redirect)
	{
		redirect.addFlashAttribute("flashError", e.getMessage());
		return "redirect:/reserves";
	}
}
