# Core and LiFi commits

Treat `.private/lifi` as an independent repository. A request to commit or
push DeadlinerCore covers Core only. Leave existing LiFi edits with their
owner and report them if the Core change depends on them.

When the user explicitly includes LiFi in a coordinated change, review its
diff separately. Push the intended LiFi commit to the private remote first,
then update `lifi.revision` in Core to that exact SHA. Never force-add the
private checkout to the Core index.
