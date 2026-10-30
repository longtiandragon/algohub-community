export const isAdminHost = window.location.hostname === 'admin.algohub.top'
export const isPublicHost = ['algohub.top', 'www.algohub.top'].includes(window.location.hostname)
export const adminLoginPath = isAdminHost ? '/' : '/admin/login'
