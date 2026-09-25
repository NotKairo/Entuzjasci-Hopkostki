// Uprawnienia Discorda jako bity (BigInt) + liczenie pozycji ról i uprawnień członka.

export const P = {
  KICK_MEMBERS: 1n << 1n,
  BAN_MEMBERS: 1n << 2n,
  ADMINISTRATOR: 1n << 3n,
  MANAGE_CHANNELS: 1n << 4n,
  ADD_REACTIONS: 1n << 6n,
  VIEW_CHANNEL: 1n << 10n,
  SEND_MESSAGES: 1n << 11n,
  MANAGE_MESSAGES: 1n << 13n,
  EMBED_LINKS: 1n << 14n,
  READ_MESSAGE_HISTORY: 1n << 16n,
  USE_EXTERNAL_EMOJIS: 1n << 18n,
  MANAGE_ROLES: 1n << 28n,
  SEND_MESSAGES_IN_THREADS: 1n << 38n,
  MODERATE_MEMBERS: 1n << 40n,
};

export function has(bitfield, permission) {
  const bits = BigInt(bitfield ?? 0);
  if ((bits & P.ADMINISTRATOR) === P.ADMINISTRATOR) return true;
  return (bits & permission) === permission;
}

export function rolesById(roles) {
  return new Map(roles.map((role) => [role.id, role]));
}

// Najwyższa pozycja spośród ról członka (0 = tylko @everyone).
export function highestPosition(roleIds = [], byId) {
  return roleIds.reduce((max, id) => Math.max(max, byId.get(id)?.position ?? 0), 0);
}

// Uprawnienia członka na poziomie serwera: @everyone + wszystkie jego role.
export function memberPermissions(roleIds = [], byId, guildId) {
  let bits = BigInt(byId.get(guildId)?.permissions ?? 0);
  for (const id of roleIds) bits |= BigInt(byId.get(id)?.permissions ?? 0);
  return bits;
}
