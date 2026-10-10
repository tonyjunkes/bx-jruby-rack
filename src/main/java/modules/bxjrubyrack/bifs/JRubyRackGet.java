package modules.bxjrubyrack.bifs;

import modules.bxjrubyrack.RackRuntime;
import ortus.boxlang.runtime.bifs.*;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.scopes.*;
import ortus.boxlang.runtime.types.*;

/** Looks up an existing Rack application in an explicit gateway scope. */
@BoxBIF
public final class JRubyRackGet extends BIF {
    /**
     * Declares the identifier and optional gateway scope for jrubyRackGet.
     */
    public JRubyRackGet() {
        declaredArguments = new Argument[] {
            new Argument(true, "string", Key.of("id")),
            new Argument(false, "string", Key.of("scope"), "default")
        };
    }

    /**
     * Looks up an app using its identifier and explicit scope.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param args the validated app ID and optional scope
     * @return the registered Rack app handle
     */
    @Override
    public Object _invoke(IBoxContext context, ArgumentsScope args) {
        return RackRuntime.get(args.getAsString(Key.of("id")), args.getAsString(Key.of("scope")));
    }
}
