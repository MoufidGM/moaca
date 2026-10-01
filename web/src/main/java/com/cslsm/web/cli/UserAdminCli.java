package com.cslsm.web.cli;

import com.cslsm.web.security.AppUser;
import com.cslsm.web.security.Role;
import com.cslsm.web.security.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.Console;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Account administration from the server's terminal. Needed to create the first super admin
 * and as a recovery path (lost phone, forgotten password) that does not depend on the web UI.
 *
 *   java -jar cslsm-web.jar --create-user=mo --role=SUPER_ADMIN --name="Moufid"
 *   java -jar cslsm-web.jar --reset-password=mo
 *   java -jar cslsm-web.jar --reset-2fa=mo       (lost phone: re-enroll at next login)
 *   java -jar cslsm-web.jar --unlock=mo
 *
 * Passwords are typed at a hidden prompt, never passed as arguments (they would end up in
 * the shell history and the process list).
 */
@Component
public class UserAdminCli implements ApplicationRunner
{
	private static final Logger log = LoggerFactory.getLogger(UserAdminCli.class);
	private static final List<String> COMMANDS = List.of("create-user", "reset-password", "reset-2fa", "unlock");
	private static final int MIN_PASSWORD_LENGTH = 12;

	private final UserRepository users;
	private final PasswordEncoder passwordEncoder;
	private final ApplicationContext context;

	public UserAdminCli(UserRepository users, PasswordEncoder passwordEncoder, ApplicationContext context)
	{
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.context = context;
	}

	public static boolean isCliInvocation(String[] args)
	{
		return Arrays.stream(args).anyMatch(a -> COMMANDS.stream().anyMatch(c -> a.startsWith("--" + c)));
	}

	@Override
	public void run(ApplicationArguments args)
	{
		if (!isCliInvocation(args.getSourceArgs()))
		{
			if (users.count() == 0)
			{
				log.warn("No user accounts exist yet. Create the super admin from the server terminal:  "
						+ "java -jar cslsm-web.jar --create-user=<name> --role=SUPER_ADMIN");
			}
			return;
		}

		int code;
		try
		{
			execute(args);
			code = 0;
		}
		catch (IllegalArgumentException | IllegalStateException e)
		{
			System.err.println("Error: " + e.getMessage());
			code = 1;
		}
		final int exitCode = code;
		System.exit(SpringApplication.exit(context, () -> exitCode));
	}

	private void execute(ApplicationArguments args)
	{
		if (args.containsOption("create-user"))
		{
			createUser(args);
		}
		else if (args.containsOption("reset-password"))
		{
			AppUser user = existing(option(args, "reset-password"));
			users.updatePassword(user.id(), passwordEncoder.encode(promptNewPassword(user.username())));
			System.out.println("Password changed for " + user.username() + " (account unlocked).");
		}
		else if (args.containsOption("reset-2fa"))
		{
			AppUser user = existing(option(args, "reset-2fa"));
			users.resetTotp(user.id());
			System.out.println("Two-factor reset for " + user.username() + ". They will scan a new QR code at next login.");
		}
		else if (args.containsOption("unlock"))
		{
			AppUser user = existing(option(args, "unlock"));
			users.unlock(user.id());
			System.out.println("Unlocked " + user.username() + ".");
		}
	}

	private void createUser(ApplicationArguments args)
	{
		String username = option(args, "create-user").trim();
		if (!username.matches("[A-Za-z0-9._-]{3,32}"))
		{
			throw new IllegalArgumentException("Username must be 3-32 characters: letters, digits, dot, dash, underscore.");
		}
		if (users.findByUsername(username).isPresent())
		{
			throw new IllegalArgumentException("User '" + username + "' already exists.");
		}
		Role role;
		try
		{
			role = Role.valueOf(option(args, "role").trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("--role must be one of RECEPTIONIST, RESTAURANT_MANAGER, ADMIN, SUPER_ADMIN.");
		}
		String displayName = args.containsOption("name") ? option(args, "name").trim() : username;

		users.create(username, displayName, passwordEncoder.encode(promptNewPassword(username)), role);
		System.out.println("Created " + role.label().toLowerCase(Locale.ROOT) + " '" + username + "'."
				+ (role.requiresTwoFactor() ? " Two-factor enrollment will start at first login — do it right away." : ""));
	}

	private AppUser existing(String username)
	{
		return users.findByUsername(username.trim())
				.orElseThrow(() -> new IllegalArgumentException("No user named '" + username + "'."));
	}

	private static String option(ApplicationArguments args, String name)
	{
		List<String> values = args.getOptionValues(name);
		if (values == null || values.isEmpty() || values.get(0).isBlank())
		{
			throw new IllegalArgumentException("--" + name + " needs a value.");
		}
		return values.get(0);
	}

	private static String promptNewPassword(String username)
	{
		Console console = System.console();
		if (console == null)
		{
			throw new IllegalStateException("Run this from an interactive terminal (a hidden password prompt is required).");
		}
		char[] first = console.readPassword("New password for %s (min %d characters): ", username, MIN_PASSWORD_LENGTH);
		char[] second = console.readPassword("Repeat password: ");
		try
		{
			if (first == null || first.length < MIN_PASSWORD_LENGTH)
			{
				throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
			}
			if (!Arrays.equals(first, second))
			{
				throw new IllegalArgumentException("Passwords do not match.");
			}
			return new String(first);
		}
		finally
		{
			if (first != null) Arrays.fill(first, '\0');
			if (second != null) Arrays.fill(second, '\0');
		}
	}
}
