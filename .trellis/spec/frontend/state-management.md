# State management
Server owns projects, runs, versions, drafts and billing. React owns transient UI selections.
Do not put owner tokens or provider keys in localStorage. Local draft state must not masquerade
as saved edits; persist confirmed drafts via the API. Reset element selection when its source
version changes. Working preview and published version are displayed independently.
