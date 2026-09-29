const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()

export const API_BASE_URL = (configuredApiBaseUrl || '/api/v1').replace(/\/$/, '')
