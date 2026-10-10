package modules.bxjrubyrack.bifs;

import modules.bxjrubyrack.RackRuntime;
import ortus.boxlang.runtime.bifs.*;
import ortus.boxlang.runtime.context.IBoxContext;
import ortus.boxlang.runtime.scopes.*;
import ortus.boxlang.runtime.types.*;

/** Creates an owned Rack application in an active servlet gateway scope. */
@BoxBIF
public final class JRubyRack extends BIF {
    /**
     * Declares the configuration accepted by jrubyRack.
     */
    public JRubyRack() {
        declaredArguments = new Argument[] {new Argument(true, "struct", Key.of("config"))};
    }

    /**
     * Boots and mounts an app in an active servlet gateway scope.
     *
     * @param context the calling BoxLang context used for path resolution
     * @param args the validated BoxLang app configuration
     * @return the booted app handle
     */
    @Override
    public Object _invoke(IBoxContext context, ArgumentsScope args) {
        return RackRuntime.create(context, args.getAsStruct(Key.of("config")));
    }
}
