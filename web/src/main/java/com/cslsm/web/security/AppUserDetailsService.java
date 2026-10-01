package com.cslsm.web.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AppUserDetailsService implements UserDetailsService
{
	private final UserRepository users;

	public AppUserDetailsService(UserRepository users)
	{
		this.users = users;
	}

	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException
	{
		AppUser user = users.findByUsername(username)
				.orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
		return User.withUsername(user.username())
				.password(user.passwordHash())
				.authorities(user.role().authority())
				.disabled(!user.enabled())
				.accountLocked(user.isLocked(System.currentTimeMillis()))
				.build();
	}
}
